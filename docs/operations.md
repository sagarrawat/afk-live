# Operations

## Processes

AFK Live runs as two processes from the same jar:

| Service | Role | What it does | Deployed |
| --- | --- | --- | --- |
| `afklive-web@a` / `@b` (was `afklive`) | `STREAMING_ROLE=web` | Website and API, billing, scheduled starts/stops | On every push to `main` (GitHub Actions), without downtime (OPS-4, below) |
| `afklive-worker` | profile `worker` | Runs live streams (FFmpeg) | Manually: **Actions → Deploy stream worker** |

They share only the PostgreSQL database. The web app records what should stream; workers claim
streams, run them, and report health every 2 seconds. Restarting the web app never touches a
running stream.

For a single small server you can also run one process with the default `STREAMING_ROLE=all`
(web + worker). Then a deploy restarts streams too, but they resume automatically within seconds.

Unit files: `afklive-scripts/systemd/`.

## How a stream runs

```
web app ── StreamJob (spec, desired state) ──► database ◄── heartbeat, health ── worker
                                                                     │
                                       encoder (one FFmpeg) ──► MPEG-TS pipe ──► relay per destination (FFmpeg -c copy)
```

- One encode per stream, copied to each destination: extra destinations cost bandwidth, not CPU.
- A destination that drops reconnects on its own (backoff 1 s → 30 s); the others keep going.
- A crashed encoder restarts with backoff; ten failures within ten minutes end the stream as failed.
- A worker that stops reporting for 45 s loses its streams to another worker.
- On restart a worker resumes the streams assigned to it (needs a stable worker id; the systemd unit uses the host name).
- Every heartbeat is fenced: it only succeeds if the row still holds the value this worker last wrote. If another
  process took the stream (or shares the id by mistake), the heartbeat fails and this worker stops its copy.
- A worker that can't reach the database for 30 s stops its streams, before anyone else may take them over (45 s).
- A stop asked for while the stream's worker is gone is completed by the web app.
- Billing starts when the stream is first on air, not while its files download.

## Web deploys without downtime (OPS-4)

**A web deploy starts the new version next to the running one and switches over when it is ready;
nobody sees an error and nobody is signed out.** Design: `docs/roadmap.md` ("Zero-downtime deploys")
and `docs/engineering-plan.md` §2.

```
            ┌──► 127.0.0.1:8080  afklive-web@a  (old version: serving, then 5 s "not ready", then stops)
Caddy ──────┤      health check: /actuator/health/readiness, every second
            └──► 127.0.0.1:8090  afklive-web@b  (new version: starting ... ready ... serving)
```

How a deploy runs (`afklive-scripts/web-deploy.sh`, called by the **Afklive-web CI & CD** workflow on
every push to `main`):

1. The new jar goes into the idle slot (`/opt/afklive/web/slots/<a|b>/app.jar`) and that slot starts.
   The running slot keeps serving. The script first refuses (and changes nothing) if a worker deploy is
   running, both web slots are already up, or less than 1.8 GB of memory is available.
2. The script waits for the new slot's readiness probe (about 70–110 s: startup and Liquibase migrations).
   A new version that exits or isn't ready within 5 minutes is stopped; the old one was never touched.
3. After 3 s (Caddy checks every second) the old slot is stopped: it answers its readiness probe with
   503 for 5 s (`WEB_SHUTDOWN_DRAIN`) while still serving, so Caddy moves new requests; then it stops
   accepting connections and finishes the requests in flight (up to 60 s, `WEB_SHUTDOWN_TIMEOUT`). A
   request that still meets a closed port is retried by Caddy on the other slot.
4. The new slot becomes the one that starts at boot; the old jar stays in its slot for `--rollback`.

Rehearsed before merging with the real jar twice, the real Caddyfile and the real script (systemd
replaced by a stand-in), on a 2-core 8 GB machine: two switches under continuous traffic, 137,626
requests, no errors, the signed-in visitor stayed signed in throughout.

**What makes two versions side by side safe:**

- **Migrations are expand-then-contract.** For about two minutes the old version runs against the
  database the new one migrated. A changeset only adds (tables, nullable or defaulted columns, indexes);
  the next release starts using it; a release after that drops what nothing reads any more. Never
  rename or drop in the release that stops using something.
- **Sign-ins are stored in PostgreSQL** (Spring Session JDBC, `spring_session` tables), so both slots
  share them. A session attribute a new version can't read (a library upgrade changed a class) counts
  as signed out for that visitor instead of an error page; remember-me signs them back in. CSRF tokens
  are in a cookie. Remember-me cookies need `REMEMBER_ME_KEY` set (otherwise each slot makes up its own
  key). The release that brings this signs everyone out once, because sessions were in memory before
  (Spring Boot 4 had stopped auto-configuring the JDBC store).
- **Every background job claims its work in the database**, so the two slots never do it twice:

| Job (web side) | Every | Why two instances are safe |
| --- | --- | --- |
| Alerts (`AlertMonitor`) | 30 s | An incident opens and closes with one conditional update. Alerts with a dedupe key are inserted under a lock on user and key, so two instances raising the same alert send it once. Each instance times "bad for 60 s" itself, so right after a deploy an alert can come up to a minute later |
| Usage metering, free-hours stop | 1 min | Each slice is added with an update fenced on `last_billing_time`; stopping is idempotent |
| Scheduled stops, abandoned stops | 15 s | Stop requests are idempotent; ending a run is one conditional update |
| Stream schedules | 20 s | Each occurrence is claimed (`claimOccurrence`) in the transaction that starts it |
| Old scheduled streams (JobRunr) | 1 min | JobRunr runs a recurring job on one server; each stream is claimed too |
| Billing period ends | hourly | One subscription at a time under a row lock, state re-read after the lock |
| Account deletion | hourly | The user row is locked; deletion is idempotent |
| Abandoned uploads | 30 min | Row lock per upload |
| New media processing | 1 min | Claimed per file; a claim older than 10 minutes is taken over. The loudness backfill may measure one file twice during the overlap (same result) |
| Playout copies | 1 min | `FOR UPDATE SKIP LOCKED` claim. A slot that stops mid-copy hands it back at once (otherwise it waited out the 8-hour stale claim) |
| Clean-ups (commands, old alerts, unplayed copies, expired sessions) | various | Conditional or idempotent deletes |
| Stored-secret re-encryption, plan defaults | startup | Conditional updates; only the starting slot runs them |

- **Per-instance memory only holds conveniences**: rate-limit counters, the plan cache (an admin plan
  edit made during the switch reaches only one slot until the old one stops: don't edit plans during a
  deploy), the status page cache (15 s).

**Never run a web deploy and a worker deploy at the same time.** Each starts a second JVM (a worker
handover also runs extra encodes). Both workflows share the GitHub concurrency group
`production-deploy`, so one waits for the other (a queued run can be replaced by a newer queued one of
either workflow: re-run the worker deploy if that happens), and the web script refuses while both
worker slots are up.

**Memory** (7.8 GB droplet). Measured in the rehearsal: a web slot at `-Xmx1g` uses 0.6–0.9 GB
resident; allow 1.3 GB after long uptime. Budget:

| | Web | Worker | PostgreSQL, Caddy, MediaMTX, OS | Left for encodes (FFmpeg) |
| --- | --- | --- | --- | --- |
| Normal | 1 × ~1.3 GB | 1 × ~0.9 GB | ~1.2 GB | ~4.4 GB (~0.25 GB per studio stream, little in copy mode) |
| Web deploy (about 2 min) | 2 × ~1.3 GB | 1 × ~0.9 GB | ~1.2 GB | ~3.1 GB |
| Worker handover (OPS-3) | 1 × ~1.3 GB | 2 × ~0.9 GB | ~1.2 GB | ~3.5 GB, with one extra encode at a time |
| Both at once (never) | 2 × ~1.3 GB | 2 × ~0.9 GB | ~1.2 GB | ~2.2 GB, plus an extra encode: live streams at risk |

Heap sizes stay as they were (web `-Xmx1g`, worker `-Xmx512m`): the deploy script's 1.8 GB check
(`MIN_FREE_MB`) stops a web deploy before it could squeeze streams. If it refuses often, check
`free -m` and the worker's stream count before lowering anything.

| Setting | Default | Meaning |
| --- | --- | --- |
| `WEB_HTTP_PORT` | `8080` (Spring default) | Per slot, in `/opt/afklive/web-slot-a.env` (8080) and `-b.env` (8090). Worker slots use 8081/8082 |
| `WEB_SHUTDOWN_DRAIN` | `0s` | How long a stopping web app reports "not ready" while still serving. The slot unit sets 5 s (`-Dapp.web.shutdown-drain`) |
| `WEB_SHUTDOWN_TIMEOUT` | `60s` | How long a stopping web app waits for requests in flight. The unit's `TimeoutStopSec=90` covers drain + this |
| Script: `READY_TIMEOUT`, `MIN_FREE_MB`, `SETTLE_SECONDS` | 300 s, 1800 MB, 3 s | Environment of `web-deploy.sh` |

The readiness probe (`/actuator/health/readiness`, also `/liveness`) answers only requests made on
the server itself; through Caddy it is 404 (and the app answers 403 to anything forwarded). Nothing
else from the actuator is exposed.

Useful: `web-deploy.sh --status`; `web-deploy.sh --dry-run /path/app.jar` (prints the steps, changes
nothing, no sudo); `sudo web-deploy.sh --rollback` (switches back to the previous version, without
downtime: it is still installed in the idle slot); `journalctl -u afklive-web@b -f`. Logs are also in
`/opt/afklive/logs/web-a` and `web-b`. If the script says both slots run (a deploy was interrupted),
look at `--status`, stop the slot that isn't the one you want (`systemctl stop afklive-web@a`), retry.

### One-time server setup (by hand, after this release is live)

The first deploy after merging restarts the old way, because the server isn't set up yet (the workflow
log says so). Do these steps **after** that, while the new version runs in `afklive.service`, as root.
No step interrupts the site:

```sh
# 0. The running version must answer the readiness probe; otherwise Caddy (step 1) would mark it down.
curl -s http://127.0.0.1:8080/actuator/health/readiness        # must print {"status":"UP"}
#    Both slots must sign remember-me cookies with the same key (empty = a random key per process):
grep -q '^REMEMBER_ME_KEY=.\+' /opt/afklive/afklive.env || echo "REMEMBER_ME_KEY=$(openssl rand -hex 32)" >> /opt/afklive/afklive.env

# 1. Caddy: both slot ports with health checks. Only 8080 (afklive.service) answers for now; that's fine.
#    Replace afk.live with your domain in the file first.
cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-ops4
cp afklive-scripts/caddy/Caddyfile /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy

# 2. Phone streaming: MediaMTX must reach the web app through Caddy, never one slot's port. In
#    /opt/afklive/ingest/mediamtx.yml:  authHTTPAddress: http://127.0.0.1:8079/internal/ingest/auth?secret=...
systemctl restart afklive-ingest      # phones reconnect on their own

# 3. Slot units, their ports and jar folders
cp afklive-scripts/systemd/afklive-web@.service /etc/systemd/system/
echo "WEB_HTTP_PORT=8080" > /opt/afklive/web-slot-a.env
echo "WEB_HTTP_PORT=8090" > /opt/afklive/web-slot-b.env
install -d -o root -g afklive -m 0755 /opt/afklive/web /opt/afklive/web/slots /opt/afklive/web/slots/a /opt/afklive/web/slots/b
systemctl daemon-reload

# 4. The deploy script (root-owned; the deploy user may run only this with sudo)
install -d -m 0755 /opt/afklive/scripts
install -o root -g root -m 0755 afklive-scripts/web-deploy.sh /opt/afklive/scripts/
echo "DEPLOY_USER ALL=(root) NOPASSWD: /opt/afklive/scripts/web-deploy.sh" > /etc/sudoers.d/afklive-web-deploy
chmod 0440 /etc/sudoers.d/afklive-web-deploy && visudo -c   # DEPLOY_USER: the user in the DO_USER secret

# 5. Rehearse (prints what will happen, changes nothing), then move from afklive.service to slot b
/opt/afklive/scripts/web-deploy.sh --dry-run /opt/afklive/app.jar
/opt/afklive/scripts/web-deploy.sh /opt/afklive/app.jar
/opt/afklive/scripts/web-deploy.sh --status                    # running: b, ready: yes (port 8090)
```

From then on every push deploys without downtime, alternating between slots a and b (the workflow
uploads to `/opt/afklive/incoming/web/`). Re-run step 4 whenever the script changes. The old
`afklive.service` stays installed but disabled. To undo: `systemctl enable --now afklive` (it takes
port 8080, so first `systemctl disable --now afklive-web@a` if a runs), then
`systemctl disable --now afklive-web@b`, restore `/etc/caddy/Caddyfile.before-ops4` and reload Caddy,
put MediaMTX back, remove `/opt/afklive/scripts/web-deploy.sh` (the workflow then restarts the old way).

## Worker settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `STREAMING_WORKER_ID` | host name + random suffix | Set a stable id (the systemd unit passes the host name) so a restarted worker resumes its streams at once instead of after takeover. Never give two running workers the same id |
| `STREAMING_WORKER_MAX_STREAMS` | 20 | Streams this worker accepts. Size it to CPU: a re-encoded 1080p30 stream needs about 1 vCPU, a stream-copy one almost none (see the spec's cost section) |
| `STREAMING_WORK_DIR` | `data/worker` | Where each stream's files are downloaded; cleaned up when it ends |
| `APP_STREAMING_COPYMODE` | `false` | FO-2 copy mode (below). Set on workers; the web side always prepares the copies workers ask for |
| `APP_STREAMING_WORKER_MAXCPUTOCLAIM` | `0.85` | Above this machine CPU (0–1) a worker leaves new streams to others for 45 s, then starts them anyway |

## Copy mode (FO-2)

**A playlist that needs nothing drawn or mixed live plays by stream copy: about 0.01 of a CPU instead
of about 0.6.** A stream qualifies when it is a playlist with no overlays, no music bed, normal video
volume, no phone input and is on its LIVE scene. For each video the web side prepares a *profile copy*
once (`media_playout_copies`, kind `PROFILE`): the file encoded exactly as the stream sends it (canvas,
frame rate, fit, loudness, bitrate), at the lowest priority.

- A qualifying stream starts in copy mode when its copies are ready; otherwise it starts in the studio
  and moves to copy mode within a minute of its copies being ready.
- Any live change (skip, scene, overlay, playlist edit) moves it back to the studio first, at the
  same playlist position. A few minutes later it may move to copy mode again.
- Both moves happen without reconnecting any destination: the new producer starts next to the old one
  and takes over at its first keyframe, and `TsStitcher` keeps the timestamps continuous. The same
  mechanism restarts a crashed producer without dropping destinations.
- Turn it on for a worker by setting `APP_STREAMING_COPYMODE=true` and restarting it; turn it off the
  same way. Streams in copy mode show `"mode":"copy"` in their now-playing data.

## Capacity guard (GL-7, RE-10)

**Before a stream goes live, pre-flight estimates the CPU its quality needs and compares it with what
workers report as free; while it runs, a stream slower than real time for a minute alerts.**

- The estimate (`CapacityEstimator`): about 0.5 of a CPU to encode 720p30, 1.0 for 1080p30, 2.0 for
  1080p60 (scaled by frame rate; 1440p ≈ 1.8 and 2160p ≈ 4 per 30 fps), plus 0.1 for decoding; 0.01 for
  a stream that plays by copy. Free capacity is the best worker with a free slot: 85 % of its CPUs minus
  its current load, from `stream_workers` (workers report `cpus` since changeset 066; an older worker
  counts as one CPU per stream slot). Calibrated on 24 Oct 2026 (4-vCPU afklive-1 fell behind at 92 %).
- Not enough room: the creator sees a warning with "Use 720p 30 fps" (or the best lighter quality that
  fits) and "Go live anyway". Only a quality more than one whole worker can give is refused. No worker
  reporting load: no warning. Scheduled starts are never held back by the warning.
- The web side counts copy mode only when it has `APP_STREAMING_COPYMODE=true` too; set it on web
  instances with the same value as on workers, or copy-eligible streams are estimated at studio cost.
- RE-10: the alert monitor (web) opens a `SLOW` row in `stream_incidents` when a run's `speed` stays
  below 0.95 for 60 s, alerts the creator (at most once per stream in 6 hours), shows it on the stream
  page and the Admin live row, and closes it when speed recovers. It doesn't count as downtime.
- One live run per stream: `uq_stream_jobs_one_live_per_stream` (changeset 065). A second Go live while
  the run is starting or live returns that run.

## Egress firewall (required in production)

Destinations are validated to be public, but FFmpeg resolves hosts again when connecting. Run the
worker as its own user and load `afklive-scripts/worker-egress.nft`, which blocks that user from
private, loopback and cloud-metadata addresses. Edit the allowed entries (database, DNS resolver, Loki, storage) for
your setup first. The role, profiles and worker id are passed as `-D` properties in the units, so
`afklive.env` can't accidentally override them.

## Phone streaming: the ingest server

Phones publish to an ingest server ([MediaMTX](https://github.com/bluenviron/mediamtx)); the worker
running the stream reads the phone's feed from it and puts it in the cloud studio (BRB scene when the
signal drops). Run it close to streamers (latency and continuity: an India region for Indian
creators), on each worker host or on its own small server.

1. Install the binary and `afklive-scripts/mediamtx.yml` under `/opt/afklive/ingest/`, and the unit
   `afklive-scripts/systemd/afklive-ingest.service`. Open TCP 1935 (RTMP) and UDP 8890 (SRT) to the internet.
2. In `mediamtx.yml` set `authHTTPAddress` to the web app with the shared secret. **Use HTTPS (or a
   private network) for it**: every auth call carries the secret and the phone's stream key. Don't log
   query strings for `/internal/ingest/auth` on any proxy in front of the web app. On the web app's own
   server use Caddy's local listener `http://127.0.0.1:8079/...`, never a web slot's port (each web
   deploy stops one slot; see "Web deploys without downtime").
3. In `afklive.env` (web and workers):

| Variable | Example | Meaning |
| --- | --- | --- |
| `INGEST_PUBLIC_HOST` | `ingest.afklive.com` | What creators put in their phone app; empty turns phone streams off |
| `INGEST_RTMP_PORT` / `INGEST_SRT_PORT` | `1935` / `8890` | Public ports |
| `INGEST_READ_URL` | `rtmp://10.0.0.5:1935/live/{key}?user={user}&pass={pass}` | Where workers read a phone's feed (workers only; never stored in the database) |
| `INGEST_READ_USER` / `INGEST_READ_PASS` | `worker` / random | Workers' read credentials |
| `INGEST_READ_ALLOWED` | `10.0.0.0/8` | Networks workers read from (default: private networks and localhost); reads from anywhere else are refused even with the right password |
| `INGEST_AUTH_SECRET` | random | Shared with `mediamtx.yml` |

Phones can publish only while their stream is live (they retry on their own, so starting the app a
little before Go Live is fine). If the worker egress firewall is loaded and the ingest server is on a
private address, add an accept rule for it (TCP 1935) in `worker-egress.nft`.

## Secrets at rest

`APP_ENCRYPTION_KEY` (32 random bytes, base64: `openssl rand -base64 32`) encrypts stream keys, ingest
keys, run specs and two-step sign-in secrets. **The app refuses to start without it.** Keep it in a password manager: without it
the stored keys can't be read. On first start of this version, keys saved earlier in plain text are
encrypted automatically.

To rotate: put the current key in `APP_ENCRYPTION_OLD_KEYS` (comma-separated), set a new
`APP_ENCRYPTION_KEY`, restart; everything is re-encrypted with the new key at startup. Remove the old
key from `APP_ENCRYPTION_OLD_KEYS` after that.

## Account security and deletion (ON-6, ON-7)

- **Two-step sign-in** (TOTP, `TwoFactorService`): after a password or Google sign-in, an account with
  it on is only partly signed in until the code is entered at `/login/2fa`; `AccountGateFilter` (in the
  security chain) allows nothing else, and the API answers 401. Remember-me sign-ins ask for the code
  again. Five wrong codes in a row lock code entry for 10 minutes (counted in `users`, so it holds across
  instances); `/login/2fa` is also limited per IP. Recovery codes are stored as SHA-256 hashes only.
  A creator who lost both phone and recovery codes: an admin can clear `two_factor_secret` after
  checking who they are.
- **Deletion**: "Delete my account" closes the account at once (live runs stop, schedules and go-live
  refuse, every session is signed out on its next request) and sets `deletion_scheduled_for` to 7 days
  later. Signing in during that time offers "Keep my account". The web side checks hourly
  (`AccountService.purgeDue`) and deletes due accounts: rows cascade from `users`, library files and
  open uploads are removed from storage, connected-channel tokens are deleted. Invoices stay for the
  tax records, detached from the account; B2C invoices (no GSTIN) lose the customer's name and address,
  B2B invoices keep the legal name, address and GSTIN that GST law requires.

## Email (OPS-1)

Email goes through ZeptoMail: set `ZEPTOMAIL_URL` and `ZEPTOMAIL_TOKEN` on the web app, and
`APP_BASE_URL` to the public address (`https://afklive.in`): verification and reset links are built
from it, never from the request. While either ZeptoMail variable is empty:

- the web app logs `EMAIL IS OFF` at startup and Admin shows a red banner;
- password sign-ups are verified at once (logged), and at startup any sign-up still waiting for a
  verification email is verified too (accounts an admin disabled are left alone);
- "Forgot password" says email is unavailable and points to support@afklive.in (or Google sign-in)
  instead of claiming a link was sent. For someone who writes in, check who they are (the address they
  write from, a payment id), then Admin > Users > the user > Emails > "Password reset link" (audited,
  works once) and send them the link;
- alerts and payment reminders still appear in the app, just not by email.

Every send is recorded in `email_log` (time, kind, the recipient's account, sent or why not; never the
address or body) and shown in Admin > Users > the user > Emails. "Send a test email" (Admin > Platform, or the
banner) sends to your own address and is audited. Records older than 180 days are removed.

## Errors and request ids (OPS-2)

Every request gets an 8-character id: in each log line it writes (`[abcd2345]`), in the `X-Request-Id`
header, on the error page as "Code: abcd2345", and in the app's message for an API server error.
Only a server error (5xx) says "something went wrong on our side"; a 4xx says what happened. A creator who quotes a code can
be found with a log search for it. A form opened before a deploy (expired security token) is not an
error: sign-in forms open again with "This page expired", and the app gets a fresh token and retries.

## Support view of a run (AD-6)

Admin > Live streams (click the stream) or Admin > Users > the user > Stream runs opens the run: source
files with size, frame rate and shape; the output profile; each destination's server, the key masked as
the creator sees it (`••••` and the last four), its length and the same key check the app runs on save;
the platform's last error, reconnects and, once the stream servers report it (RE-8), whether the
platform is receiving; and the last 200 FFmpeg lines. "Send a test" runs the usual 10-second
destination test with the run's address (not while that destination is live, one at a time per
account, not counted against the creator's hourly tests), needs a reason and is audited.

FFmpeg lines are captured on the worker (`JobLogs`): every line passes `Redact.line` before it is kept
(stream URLs, every part of the run's destination addresses in plain and URL-encoded form, and named
values such as `key=`, `streamid=`, `passphrase=`, `token=`), only the last 200 are kept in memory, and
a background thread saves them to `stream_job_logs` every 10 s while there are new ones and once when
the run stops (one row per run, replaced; removed after 14 days by the web app). A database outage only
delays the save; it never touches the stream. Runs on workers older than this release have no log.

## Destination tests

"Test" on a destination runs on a stream worker (behind its egress firewall), at most two at a time
per worker; a test no worker picks up fails after two minutes with an explanation.

## Billing

Plans are priced per concurrent stream (Free, Creator, Pro, Agency). Defaults live in `PlanType`; the
live values, prices included, are rows in `plan_configs` that an admin edits in Admin > Plans
(`/studio/admin/plans`) without a deploy. An edit needs a reason, is validated (prices never negative,
Free stays free, a yearly price at most 12 monthly ones, sensible limits), lands in `admin_actions`
and applies at once: limits for everyone on the plan, prices for every checkout and renewal from then
on. When a release changes the defaults, bump `PlanConfig.CURRENT_DEFAULTS` and the rows are
refreshed at startup (this overwrites admin edits).

Admin > Platform also holds the free plan capacity (`app_settings.global_max_streams`): free accounts
can't go live while that many streams, all plans together, are live. Support tickets are handled in
Admin > Support (status changes audited; attachments always download, never render on our origin).
The old server-rendered `/admin` page is gone; `/admin/**` redirects to `/studio/admin`.

- Payments are one-off checkouts (UPI, cards) through the payment gateway. A subscription is a period
  (monthly or yearly); renewal is manual: a reminder 3 days before the end, then 3 days' grace
  (`PAST_DUE`), then the account moves to Free. Upgrades start now and credit the unused part of the
  current period; downgrades and cancellations take effect at the period end.
- Every payment issues a GST invoice, numbered `AFK/<financial year>/<n>` with no gaps. Prices include
  18 % GST, split CGST + SGST when the customer's state (from their GSTIN or billing profile) equals the
  seller's, IGST otherwise; USD invoices are zero-rated exports.
- Streamed time is metered per user per month (`usage_months`); the Free plan's monthly hours stop its
  streams when used up. Free streams carry a small "AFK Live" mark added on the server.
- The period check runs hourly on web instances (`app.streaming.role` other than `worker`); each
  subscription is handled under a row lock, so several web instances are safe.

Set these before taking payments:

| Variable | What |
|---|---|
| `APP_BILLING_SELLER_NAME` | Legal name on invoices |
| `APP_BILLING_SELLER_ADDRESS` | Registered address |
| `APP_BILLING_SELLER_GSTIN` | Your GSTIN (leave empty until registered; invoices then omit it) |
| `APP_BILLING_SELLER_STATE` | Two-digit GST state code, e.g. `29` for Karnataka |
| `APP_BILLING_SAC` | Service code, default `998439` |

## Media library and uploads

Uploads are resumable (ML-1): the browser asks `/api/media/uploads` for an upload, then sends 16 MB
parts. With S3-compatible storage each part goes **straight to the bucket** with a presigned URL, so
upload traffic never touches the web servers; with local storage (dev) parts come through
`PUT /api/media/uploads/{id}/parts/{n}`. A page refresh resumes from the parts already stored. The
file's size is reserved against the plan's storage when the upload starts; uploads untouched for 24 h
are aborted and give the space back.

The bucket needs a CORS rule so browsers can PUT parts and read the ETag:

```json
[{"AllowedOrigins": ["https://afk.live"], "AllowedMethods": ["PUT"], "AllowedHeaders": ["*"],
  "ExposeHeaders": ["ETag"], "MaxAgeSeconds": 3600}]
```

Also add a lifecycle rule that aborts incomplete multipart uploads after 2 days, as a backstop.

New files start as Processing. A web instance claims each one, probes it with ffprobe over a
presigned URL (ranged reads, no full download), stores a thumbnail and marks it Ready or Failed with a
plain reason. Files aren't re-encoded: the studio re-encodes at playout anyway. A claim older than
10 minutes is taken over, so a restart never leaves a file stuck. Web instances therefore need
`ffmpeg`/`ffprobe` on PATH, like workers.

After a video is Ready, the same instance checks whether it is one still picture (GR-9): six small
grey frames grabbed with a seek each (5% to 95% of the length) and compared. The result goes in
`media_assets.still_picture` (null when a frame couldn't be read, so the check never guesses). Videos
uploaded before this are measured by the media sweeper, two a minute, videos in a playlist first.

## Go-live check (GR-9)

The stream check on a stream's Overview (`/api/streams/{id}/check`) reads only what AFK Live has: the
playlist, overlays, the measured still-picture flag, the YouTube title and thumbnail the creator typed
in (`streams.youtube_title`, `thumbnail_media_id`) and replies AFK Live posted (`engagement_activity`).
It never blocks going live and never calls YouTube. Thumbnail contrast is measured once per image per
web instance (in memory) from the library thumbnail. Once a week per stream that was live in the past
seven days, one web instance (conditional update on `streams.check_weekly_at`) sends an in-app notice
listing open flags; no email or push. `app.check.weekly-auto=false` turns the hourly pass off (tests).

## Push alerts (NT-1, MB-2)

Alerts that are emailed are also pushed to every device where the creator turned on
"Alerts on this device" (Settings > Notifications): phones (Android, or iPhone with AFK Live added
to the Home Screen) and computers. The web app sends them itself with VAPID (`PushService`,
`util/WebPush`, no third-party service); the browser shows them through `/sw.js`.

- `APP_PUSH_VAPID_KEY`: a P-256 key, SEC1 DER in base64. `provision-single-server.sh` adds one;
  otherwise run `afklive-scripts/vapid-key.sh`. Empty means push is off and the setting is hidden.
  Keep the key: a new one makes every device turn alerts on again.
- Only real browser push services are ever called (FCM, Mozilla, Apple, Windows; https on 443). A
  device the push service reports gone (404/410) is removed.
- The web app needs outbound HTTPS to those services (the worker doesn't).

## Status page (RE-7)

`/status` (public, `site/status.html`) reads `/api/public/status` (`StatusService`, cached 15 s per
instance). It only ever says operational / degraded / down per part, never names or counts:

- **Website and studio**: up if the page answers and the database checks below succeed.
- **Stream servers**: workers write a row in `stream_workers` every 10 s (`WorkerStatusReporter`).
  None in the last 45 s: down. A stream that should run but whose worker stopped reporting, or that
  has waited over 2 minutes for one: degraded.
- **Phone streaming** (only when `INGEST_PUBLIC_HOST` is set): MediaMTX has no API open to us, so
  this is what we saw: an auth-hook call with the right secret in the last 15 minutes
  (`status_signals`), or a worker receiving a phone's feed now. Neither: "no recent check", left
  out of the overall status.
- **Incidents**: posted and updated by admins in Admin > Platform (investigating, cause found,
  monitoring, resolved), audited in `admin_actions`. Any unresolved incident makes the overall
  status at least degraded. The last 90 days are shown.

Linked from the site footer, the app's account menu and Help page, and every email's footer.

## Admin: workers, cost and YouTube quota (AD-2, YT-4)

| Variable | Default | Meaning |
| --- | --- | --- |
| `APP_MONTHLY_SERVER_COST` | empty | The monthly bill for all servers, a plain number. Admin divides it by the last 30 days' stream hours (all runs, from `stream_jobs`). Empty: no estimate |
| `APP_SERVER_COST_CURRENCY` | `INR` | Currency of the number above |
| `APP_YOUTUBE_DAILY_QUOTA` | 10000 | The Google Cloud project's YouTube Data API units per day; Admin > Platform shows today's use (resets at midnight Pacific) |

Worker CPU and memory are the machine's (or container's), since FFmpeg runs as child processes;
memory "used" leaves out reclaimable page cache (`MemAvailable`).
