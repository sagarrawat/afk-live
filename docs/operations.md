# Operations

## Processes

AFK Live runs as two processes from the same jar:

| Service | Role | What it does | Deployed |
| --- | --- | --- | --- |
| `afklive-web@a` / `@b` (was `afklive`) | `STREAMING_ROLE=web` | Website and API, billing, scheduled starts/stops | On every push to `main` (GitHub Actions), without downtime (OPS-4, below) |
| `afklive-worker@a` / `@b` (was `afklive-worker`) | profile `worker` | Runs live streams (FFmpeg) | Manually: **Actions → Deploy stream worker**; streams are handed to the new version without a break (OPS-3, below) |

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
- **First live confirmed (ON-8).** Every 30 s each web instance runs one conditional update that sets
  `users.first_live_at` for accounts that have none yet and whose stream a platform received: the earliest
  `REACHED` timeline line of any of their runs, or now for a destination `ON` in a live run (in case that line
  was lost). `WHERE first_live_at IS NULL ... RETURNING` means one instance claims each account. It's the
  activation metric (the admin funnel's first-live step) and triggers the invite credit. `app.first-live.auto=false`
  turns the sweep off (tests). Accounts that reached a platform before this shipped are backfilled by the first
  sweep with their historical time, and their invites are credited then.
- **"Live" means the platform is receiving (RE-8).** The worker counts the bytes each relay actually takes from
  the encoder (a relay that can't open its output exits; one whose connection hangs stops reading). A destination
  is `ON` after 10 s of steady sending and `NOT_REACHING` after two 10 s windows without it (one reconnect never
  flips it, a reconnect loop does); `stream_job_destinations.reach` / `reach_reason` hold it. The badge is Live /
  Live with problems / Not reaching anyone. Every destination not reaching for 60 s opens an `UNREACHED` incident
  (counts as an outage in uptime) and alerts; one destination not reaching for 5 min alerts on its own (at most
  hourly). Reconnects, not-reaching and recoveries are on the run's timeline (`stream_events`, one line per repeated
  reason). `stream_job_destinations.bytes_sent` is written with the heartbeat every 30 s (for FO-3 cost metering).
  In a handover (OPS-3) the new copy reports nothing until it owns the run, then writes its reach; a destination
  that was `ON` starts `ON` there (so the move shows no "Connecting" or false "Not reaching"), the move back to
  YouTube's primary ingest carries the state over to the new connection, and bytes carry on from the old owner's
  last count (it writes it just before handing over).

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
  "Sign out other devices" and a password change (Settings > Sign-in & security) delete the account's
  other rows in `spring_session` (found by principal name, which is the email for password and Google
  sign-ins) and raise `users.sessions_version`, which is part of every remember-me cookie's signature
  once above 0 (`SessionRememberMeServices`), so those cookies stop working too; the device that asked
  gets a fresh cookie. Confirming a new email ends every sign-in of the account (sessions are filed
  under the old address). Changing email needs email to be on (`ZEPTOMAIL_*`); without it the page says
  it isn't available yet.
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
| Unplayed copies (FO-6) | 1 min | Each delete is conditional on the row as read (status, file, age), so only one slot deletes a copy and its file; never a copy without overlays of a video a live run plays (copies with overlays go by their own last play, refreshed every 6 h while played) |
| Traffic budget alerts (FO-5) | 10 min | The `transfer_alerts (month, threshold)` insert is the claim: only the slot whose insert lands alerts admins |
| Fair use (FO-5) | 5 min | Each creator alert is claimed by the `traffic_notices (user, month, step)` insert. Pausing and restoring destinations rewrite the run's destination list under the stream's row lock and only when it changes, so two slots never send a second command |
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
#    The file names afklive.in (and redirects www. and http:// to it); change the domain if yours differs.
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
| `WORKER_CONTROL_TOKEN` | empty | OPS-3: shared by the worker and the deploy script, which asks the running worker to hand its streams over (`/internal/worker/*`, localhost only). Empty: the endpoint refuses everything and only restart deploys work |
| `WORKER_HTTP_PORT` | `8081` | The worker's localhost-only web server (control endpoint). The slot units set it per slot (8081 for a, 8082 for b) |
| `STREAMING_HANDOVER_TIMEOUT_SECONDS` | `60` | A new copy not on air everywhere within this time is abandoned; the old worker keeps the stream |
| `STREAMING_HANDOVER_READY_SECONDS` | `5` | How long the new copy must be live everywhere it publishes before the old one stops |
| `STREAMING_HANDOVER_RETURN_TO_PRIMARY` | `true` | After a handover, move YouTube destinations from the backup ingest back to the primary (make-before-break, same encoder) |
| `APP_STREAMING_X264PRESET` | `superfast` | x264 speed preset of the live encoder (below). Set the same value on workers and web |
| `APP_STREAMING_X264THREADS` | `0` | Threads per live encoder; `0` lets x264 decide (below) |
| `STREAMING_WORKER_POOL` | `main` | FO-8: the pool this worker belongs to (shown in Admin > Servers). See "Worker pools" |
| `STREAMING_PHONE_POOLS` | `main` | FO-8: pools whose workers can read the phone ingest server; only they run phone streams. Empty: every pool. Same value on every worker |
| `STREAMING_POOL_PREFERENCE` | empty | FO-8: preferred pool per kind of run, e.g. `PLAYOUT=playout,PLAYLIST=playout`. Same value on every worker |

## Live encoder

Every studio stream (overlays, scenes, phone input, or a playlist not in copy mode) has one x264
encoder. Fixed, because YouTube requires it: constant bitrate with filler, a keyframe every 2 s, AAC
128 kbps 44.1 kHz stereo. The video bitrate is per plan (below); with nothing set it is the built-in
ladder: 720p 4 Mbps, 1080p 6 Mbps, 1440p 9 Mbps, 2160p 15 Mbps, 60 fps ×1.5. Configurable by
environment, read at start (restart the process to change it):

- **`APP_STREAMING_X264PRESET`** (default `superfast`): one of `ultrafast`, `superfast`, `veryfast`,
  `faster`, `fast`, `medium`. Anything else is logged as a warning and `superfast` is used. Measured on
  720x1280 30 fps footage (encoder CPU per second of video): grainy 0.57 → 0.32 cores from veryfast to
  superfast with SSIM 0.888 → 0.887; typical 0.59 → 0.39 (0.9764 → 0.9749); easy 0.21 → 0.14.
  `ultrafast` is visibly worse (SSIM 0.944 on typical footage); slower than `veryfast` costs more CPU
  than its quality gain is worth at these bitrates.
- **`APP_STREAMING_X264THREADS`** (default `0`, auto): caps threads per encoder (1–16). Two and four
  encoders in parallel used the same total CPU (within noise) with caps of 1, 2, 3 or auto, so there's
  no default cap. A cap only bounds latency spikes on a crowded worker; it doesn't save CPU.
- The worker logs `Live encoder: x264 preset …, threads …` at start.

### Video bitrate per plan (FO-4)

**`APP_STREAMING_BITRATE_<PLAN>_<CLASS>`** sets the video bitrate in kbps at 30 fps (60 fps streams get
1.5× of it) for one plan and height class. `<PLAN>` is `FREE`, `ESSENTIALS` (shown as Creator), `PRO`,
`AGENCY`, or `DEFAULT` for every plan without its own value; `<CLASS>` is the picture's short side:
`720` (anything under 1080, vertical 720x1280 included), `1080`, `1440` or `2160`. Example:
`APP_STREAMING_BITRATE_FREE_720=3000` (property `app.streaming.bitrate.free.720`). A plan's own value
wins, then `DEFAULT`, then the built-in ladder. The web and worker logs print the table at start:
`Live video bitrates (kbps at 30 fps): FREE 720=… 1080=…`.

- **Floors.** A value below YouTube's ingest guidance is raised to it with a warning: 720 → 1,500
  (the bottom of YouTube's 720p30 range), 1080 → 5,000 (repo rule: 1080p30 ≥ 5 Mbps), 1440 → 6,000,
  2160 → 13,000. 1.5× of each floor also meets YouTube's 60 fps ranges. Anything that isn't a whole
  number (a trailing `k` is fine) is ignored with a warning; above 50,000 is capped. A typo never stops
  a process.
- **Set it on the web instances.** The web app picks the bitrate once, when a run goes live, from the
  owner's plan at that moment, and records it in the run's spec (`studio.videoKbps`). Workers encode at
  the recorded value. So a new value or a plan change applies to the **next** run; a running stream
  keeps its bitrate through live edits, restarts, takeovers and deploys (it never reconnects for it).
  Runs from before this was recorded use the built-in ladder. Set the same values on workers too, so
  the start-up logs agree (workers don't use them otherwise).
- **Needs a worker deploy** of the release that reads `videoKbps` before any value is set: an older
  worker ignores it and sends the built-in rate (which, unchanged, is the same thing).
- **Copy mode.** Profile copies (with or without static overlays) are made at the run's bitrate and
  kept per bitrate as well as per overlay set (`media_playout_copies.video_kbps`; 0 = the built-in
  ladder's, which every copy made before this has),
  so a copy at another bitrate is never spliced into a stream. Changing a plan's value means each video
  gets a new profile copy, made in the background the first time a run at the new rate asks for it (those
  runs use the studio meanwhile); copies at the old rate are deleted after 30 days unplayed (FO-6).
- **Health.** The bitrate check on the stream page and in admin compares against the run's recorded
  bitrate, so a lower plan bitrate doesn't show as "below the usual quality".
- **Cost.** FO-3 (`CostMeter`) counts the bytes destinations actually sent, so a change shows up in
  the cost panel's GB per hour on its own; it has no bitrate assumptions to update.

**Traffic per 24/7 stream, per destination** (video + 128 kbps audio, 30 days, before ~2–5% protocol
overhead; every destination is a separate copy, so two destinations send twice this):

| Video bitrate | Total kbps | GB/day | TB/month |
|---|---|---|---|
| 2.5 Mbps | 2,628 | 28.4 | 0.85 |
| 3 Mbps | 3,128 | 33.8 | 1.01 |
| 3.5 Mbps | 3,628 | 39.2 | 1.18 |
| 4 Mbps (720p30 today) | 4,128 | 44.6 | 1.34 |
| 6 Mbps (1080p30, 720p60 today) | 6,128 | 66.2 | 1.99 |
| 9 Mbps (1080p60 today) | 9,128 | 98.6 | 2.96 |

(TB/month = kbps × 0.000324. One droplet's 5,000 GB included transfer is about 3.7 streams-and-destinations
at 4 Mbps for a whole month, 4.9 at 3 Mbps.)

**Try it on Free first.** Lower `APP_STREAMING_BITRATE_FREE_720` (Free is 720p30 only), watch a few
Free streams on YouTube (Stats for nerds, and YouTube Studio's stream health for "lower than
recommended" warnings) and the cost panel for a few days, then decide on paid plans. Measure first with
`afklive-scripts/bitrate-study.sh` on real footage (gameplay, a music still, a talking head): pick the
lowest rate whose SSIM is within 0.005 of 4 Mbps for the footage creators actually stream.

### Presets and copy mode

Copy mode hands a stream between a profile copy and the studio encoder
without reconnecting, so both must write the same H.264 parameter sets. Presets fall into families
with byte-identical SPS/PPS (checked against real x264 by `EncoderSettingsFfmpegTest`): `superfast`
and `veryfast` are one family; `ultrafast`, `faster`, `fast` and `medium` are each their own. Each
profile copy records the preset it was made with (`media_playout_copies.x264_preset`, empty = made
with `veryfast` before this setting existed). A worker plays only copies of its own family; a ready
copy of another family is made again with the worker's preset unless a worker played it within the
hour. So:

- Switching between `superfast` and `veryfast` needs nothing else: every existing copy stays valid.
- Switching to another family re-encodes each profile copy once, in the background, as streams ask
  for them (those streams use the studio meanwhile). Set it on all workers in the same deploy, or
  workers on different families keep the copy for whichever is playing it.

## Copy mode (FO-2)

**A playlist that needs nothing drawn or mixed live plays by stream copy: about 0.01 of a CPU instead
of about 0.6.** A stream qualifies when it is a playlist with only static overlays (below), no music bed,
normal video volume, no phone input and is on its LIVE scene. For each video the web side prepares a
*profile copy* once (`media_playout_copies`, kind `PROFILE`): the file encoded exactly as the stream sends
it (canvas, frame rate, fit, loudness, bitrate, and the requesting worker's x264 preset; see "Live
encoder"), at the lowest priority.

**Static overlays are baked into the copies, so Free plan streams (the "AFK Live" mark) qualify too.**
Static means drawn the same every frame: the Free plan's mark, fixed text, and a logo (image) at a fixed
place, size and opacity. Dynamic overlays keep a stream in the studio: a clock, a countdown, a scrolling
ticker, and text with a placeholder (`{title}`, `{next}`, `{track}`, or anything in braces). Hidden
overlays and overlays on other scenes don't count. (`StaticOverlays`; the reason a stream stays in the
studio is in the worker's debug log.)
- Copies with overlays are kind `PROFILE_OVERLAY`, one per *overlay set*: `overlay_set` is a SHA-256 of
  everything that decides the picture (canvas, kind, mark or not, position, size, text, image library id
  and stored file, colours, opacity, font, drawing version); `overlay_spec` is what to draw. Copies
  without overlays keep `overlay_set = ''`. The set is part of the unique key, so a copy with the mark is
  never played for a stream without it (after an upgrade, or a logo hidden live) and the other way round.
- The web side draws the set once with the studio's own `OverlayCompositor` into one transparent
  canvas-sized frame (raw yuva420p, the studio's own colour conversion and edge alpha) and FFmpeg's
  `overlay` filter puts it on every frame after fitting and before encoding, where the studio blends. A
  copy's picture matches the studio's to within 1 level per pixel (`StaticOverlayCopyPixelTest`), so a
  switch shows no jump. `made_overlay_set` records what the maker drew; a copy without it (made by an
  older release mid-rollout) is never played and is made again.
- A live change to the set (logo moved or resized, mark gone after an upgrade, a clock added) moves the
  stream to the studio as any live change does; the next check asks for copies for the new set and moves
  back to copy mode once they're ready (or stays in the studio if something dynamic was added).
- **Cost:** every overlay set is another full set of profile copies of the stream's videos, in storage
  (the stream's bitrate × the playlist's length: ~1.9 GB per hour of playlist at 720p30, ~2.8 GB at
  1080p30; at DigitalOcean Spaces' $0.02 per GB-month beyond its included 250 GB, about $0.04–0.06 a
  month per playlist hour) and one
  background encode per video. Most streams have one set (the mark, or one logo), so for most it replaces
  the plain copies rather than adding to them. Moving a logo makes a new set; the old one is deleted 30
  days after it was last played (FO-6 below), even while the stream stays live.

- A qualifying stream starts in copy mode when its copies are ready; otherwise it starts in the studio
  and moves to copy mode within a minute of its copies being ready.
- Any live change (skip, scene, overlay, playlist edit) moves it back to the studio first, at the
  same playlist position. A few minutes later it may move to copy mode again.
- Both moves happen without reconnecting any destination: the new producer starts next to the old one
  and takes over at its first keyframe, and `TsStitcher` keeps the timestamps continuous. The same
  mechanism restarts a crashed producer without dropping destinations.
- **A hand-off doesn't move media time against the clock** (`TsStitcher`; the OPS-6 soak checks it).
  While a hand-off is pending, the old producer's output is held back 1 s (only then; relays see a
  1 s pause, then catch up). The new producer is watched for 0.4 s after its first keyframe to learn its
  real pace (its first frames can come out late and the next ones catch up, e.g. when copy mode's first
  keyframe falls inside FFmpeg `-re`'s initial burst). It's placed so its frames arrive exactly as far
  ahead of the clock as the stream's first producer's did, and the old one is cut at the last clean
  frame boundary (no frame shown out of order) that leaves room for it; what the old one sent after that
  is dropped, which is also how a stream that got ahead is pulled back (up to 1 s per switch). That
  usually means a step forward in the timeline of up to 0.15 s (viewers may see a frame or two repeated)
  and never a step back. Every switch aims at the same reference, so errors don't add up. After a crash
  whose restart left the stream more than 1 s behind, the timeline just carries on and the reference
  moves to the new pace.
  The worker logs each switch's remaining error: `switched producer without reconnecting (media time N
  ms off the clock)`.
- Turn it on for a worker by setting `APP_STREAMING_COPYMODE=true` and restarting it; turn it off the
  same way. Streams in copy mode show `"mode":"copy"` in their now-playing data.
- **Before turning it on**, run the copy-mode soak below for 6 hours and get a pass. The first soak
  (Oct 2026) found every studio↔copy hand-off moved media time about 150 ms ahead of real time, adding
  up to +4.2 s over 56 switches. Fixed (see the bullet above): the stitcher continued after the largest
  timestamp sent (an old B-frame's presentation time, or audio muxed ahead) rather than the last video
  frame, and placed the new producer after old frames that arrived after its keyframe. The same 60-min
  run (2 streams, a switch every 2 min, 56 switches) now passes: media vs clock 521 ms at worst, 0 ms
  per switch on average, and both streams within 2 ms of where they started at the end. The worst
  moments are a move to copy whose first keyframe falls inside FFmpeg `-re`'s initial 0.5 s burst: up
  to about 0.5 s ahead until the next switch takes it back.

## Soak test (OPS-6)

**Proves a long stream stays healthy before copy mode is turned on and before a worker deploy.**
`afklive-scripts/soak.sh` runs K streams for N minutes through the worker's own streaming code: the
studio (`StudioRuns` → `StudioBuilder` → `Studio`, one long-lived x264 encoder, overlays on), copy mode
(`CopyRuns` → `CopyPlayer`, stream copy of profile copies made with the web side's own command),
`StreamRunner` with production timings and the production relay command, and `TsStitcher` hand-offs.
Only the database and object storage are in-memory stand-ins. Each destination is a TCP server on
127.0.0.1 that reads the FLV the relay sends tag by tag: nothing leaves the machine and nothing large is
written. In copy mode every stream switches studio↔copy every 10 minutes, the way `StreamWorker` does.

```sh
afklive-scripts/soak.sh --minutes 10 --copy-mode --streams 2   # smoke run, ~13 min including the build
afklive-scripts/soak.sh --minutes 10 --copy-mode --watermark   # the same with the Free plan's mark baked into the copies
afklive-scripts/soak.sh --copy-mode                            # the real one: 6 h, 2 streams, 2 destinations each
afklive-scripts/soak.sh                                        # 6 h studio-only (copy mode off, as production today)
# --streams K, --destinations D, --height 720|1080, --fps 30|60, --switch-minutes M, --out DIR
```

Results go to `afklive-web/target/soak/<time>-<mode>-<minutes>m/` (or `--out`): `report.md` (the table
below with measured values), `summary.json` (every check, per-stream and per-destination numbers, the
10 s time series of memory, descriptors, threads and CPU, and the 10 s timeline windows), `soak.log`
and any sample file that failed its check. Exit code 0 means every judged check passed. It is also a
JUnit test, `SoakTest`, tagged `soak` and excluded from every normal build (`soak.excludedGroups` in
`afklive-web/pom.xml`), so it never costs CI time.

**Where to run the 6-hour soak.** On staging (OPS-5) or any spare Linux machine with at least as many
vCPUs as a production worker (4) and FFmpeg 6, never on a server that is streaming for customers: it
uses the CPU of K real streams. Run it from a checkout of the commit you mean to deploy, under `tmux`
or `nohup`, with nothing else heavy running (a shared machine makes "encoders kept up" and "dropped
frames" fail for reasons that aren't the code's). For a nightly run, a cron line on staging is enough:
`0 1 * * * cd /opt/afklive/src && git pull -q && afklive-scripts/soak.sh --copy-mode --out /var/log/afklive-soak/$(date +\%F)`.
Before a major release, run `--minutes 4320` (72 h).

**What "pass" means.** Every judged row below passes. Limits live in `Thresholds.java`; change both.

| Check | Pass when | Why |
| --- | --- | --- |
| Each destination stayed on one connection | exactly 1 TCP connection per destination for the whole run | a planned switch or restart must never reconnect (continuity rule) |
| No reconnects / not reaching | 0 `RECONNECTING`, 0 "not reaching" from `StreamRunner` | what the creator would see |
| Every stream ran to the end, no producer crashed | 0 early ends, 0 encoder/copy process failures | |
| Encoders kept up with real time | ≤ 1% of FFmpeg progress readings below 0.9x | an encoder that falls behind starves the platform |
| Studio clock dropped almost no frames | ≤ 0.5% of frames | |
| Timestamps only go forward | 0 steps back, audio or video, on every tag of every connection | |
| No hole in the timeline | largest step < 0.25 s video, < 0.5 s audio (as `TsStitcherTest`) | |
| A/V drift | ≤ 40 ms (spec OPS-6) | audio timeline against video timeline, 10 s window medians, against the first window after 30 s |
| Media timeline kept pace with the clock | ≤ 2 s change after warm-up | the platform's buffer absorbs a little, not a growing lead or lag |
| No stalls | no 2 s pause in the bytes | |
| Samples decode cleanly | every 20 s sample (cut at each switch and every 30 min, from a keyframe 4 s before) has continuous timestamps (ffprobe) and < 5 FFmpeg decode error lines | the actual bitstream, across the switch |
| Every studio↔copy switch succeeded, hand-off time | all switches, each ≤ 10 s | copy mode only |
| Heap after full GC / JVM RSS / FFmpeg RSS | ≤ 10 / 50 / 20 MB per hour (least-squares slope after warm-up) | leaks; judged only with ≥ 30 min of steady running |
| Descriptors / threads while running | peak in the last fifth ≤ peak in the first fifth + 10 / + 6 | leaks that show without a long slope |
| After stopping | descriptors ≤ +4 and threads ≤ +6 of the start (JVM pools such as `process reaper` excluded), 0 FFmpeg processes, 0 files in work folders, 0 media cache pins | everything a stream opens is closed |

Information only (shown, not judged): CPU per stream in each mode (FFmpeg plus an equal share of the
JVM), sequence headers re-sent mid-stream, the playlist position jump at a switch, and how far the media
timeline moved against the clock per switch.

**Before enabling copy mode** (`APP_STREAMING_COPYMODE=true`): a 6-hour `--copy-mode` soak on
production-like hardware passes, at the canvas production streams use (720p30 today), with the release
you deploy. **Before a worker deploy** that touches `streaming/`: a 6-hour soak in the mode production
runs passes, or at least the 10-minute smoke run while a 6-hour one is under way.

## End-to-end test

**Catches what unit tests miss: screens, API and worker disagreeing with each other.** `CreatorJourneyE2ETest`
(`afklive-web/src/e2e/java`) starts the whole app in one JVM, as on a single server: Spring Boot on a random
port, an embedded PostgreSQL (the one `PostgresIntegrationTest` starts), the stream worker in the same process
(role `all`, its loop and media checks running by themselves) with real FFmpeg, and the built React app. Playwright
for Java drives headless Chromium through:

1. sign up with a password (email is off, so the account is ready at once: OPS-1), sign in, land in setup;
2. upload a generated 6-second video through the real resumable upload, Next, "Use another platform";
3. add a destination "Other" pointing at a local RTMP sink (FFmpeg listening on 127.0.0.1) and see it turned on;
4. Go live (answering "Go live anyway" if the capacity estimate asks), wait for the badge "Live" and
   "On Test sink" (RE-8) while the sink receives data, Stop now, see "Off air" and the run ended under Recent runs;
5. at 375 px wide: setup, Streams, Library, Destinations, Schedule, Settings and Billing don't scroll sideways;
   choose Creator, see the quote in rupees ("Prices include GST"), Pay, and land on the PhonePe page. The gateway
   is a Mockito double that returns a sandbox URL and the browser answers that URL itself; the test checks the
   order was created for exactly the amount the quote showed. Nothing calls PhonePe or YouTube.

Production still refuses local stream addresses (DS-8): only the test JVM treats every host as public for the
address check (`PublicDnsForTests`), so the sink on 127.0.0.1 can be saved.

```sh
afklive-scripts/e2e.sh              # builds the app, then runs the test (about 2 minutes after the build)
afklive-scripts/e2e.sh -DskipUi     # reuse the app already built into afklive-web/target/classes/static/app
```

It needs Java 21, Maven, Node 20+, FFmpeg and Chromium for Playwright 1.56 (`npx playwright@1.56.0 install
--with-deps chromium`, or `PLAYWRIGHT_BROWSERS_PATH` pointing at an existing install, or `E2E_CHROMIUM` at a
chrome binary). It waits for what the screen shows, never for a fixed time. On failure a full-page screenshot and
a Playwright trace (`npx playwright show-trace trace.zip`) are in `afklive-web/target/e2e/<test>/`.

Normal builds skip it: the test lives in the `e2e` Maven profile (so they don't download Playwright's ~200 MB
driver) and is tagged `e2e` (excluded by Surefire like `soak`). On GitHub, run **End-to-end test**
(`.github/workflows/e2e.yml`, manual only) on a branch; it uploads the screenshots and traces when it fails.
Upgrading Playwright means changing `playwright.version` in `afklive-web/pom.xml` and the version in that workflow.

## Worker deploys without interruption (OPS-3)

**A worker deploy starts the new version next to the running one and moves live streams over one at a
time; viewers see no gap on YouTube and about 3 seconds of reconnect on other platforms.** Design:
`docs/roadmap.md` ("Zero-downtime deploys") and `docs/engineering-plan.md` §3.

How a deploy runs (`afklive-scripts/worker-handover-deploy.sh`, called by **Actions → Deploy stream
worker** with method `handover`):

1. The new jar goes into the idle systemd slot (`afklive-worker@a` or `@b`; each has its own worker id
   `HOST-a` / `HOST-b`, its own localhost port and its own jar) and starts. The script waits until its
   loop has made a pass.
2. The script asks the running worker to drain (`POST /internal/worker/drain` with
   `WORKER_CONTROL_TOKEN`). That is recorded in `stream_workers.draining_since`; admins see "Being
   replaced" on the worker. A draining worker takes on no new streams and offers its streams one at a
   time (`stream_jobs.handover_*`).
3. The new worker claims the offer and starts the stream at the old one's current playlist position
   (copy mode or studio, whichever qualifies). YouTube destinations publish to YouTube's other ingest:
   `rtmp://b.rtmp.youtube.com/live2?backup=1/KEY` when the old run uses the primary
   (`rtmp://a.rtmp.youtube.com/live2/KEY`), or the reverse; RTMPS the same with `a.rtmps` / `b.rtmps`.
   Other destinations wait, because most platforms accept one connection per key.
4. When the encoder and every YouTube destination of the new copy have been live for 5 s, it marks the
   handover ready. The old worker disconnects its other destinations, makes the new worker the owner
   (one fenced update) and stops its copy; YouTube fails over to the backup ingest. The new worker sees
   that within a quarter second and connects the other destinations.
5. A few seconds later the new worker moves YouTube back to the primary ingest: it connects the primary
   next to the backup from the same encoder and drops the backup once the primary has been up for 8 s.
   (Google's API docs say the primary "must" be streamed to; the backup is optional.)
6. When the old worker owns nothing, the script stops it, disables its slot and enables the new one.

What can go wrong, and what happens (each case has a test in `WorkerHandoverIntegrationTest` or
`WorkerHandoverDeployScriptTest`):

| Case | Result |
| --- | --- |
| New version doesn't start or isn't ready in 4 minutes | Script stops it; the old one was never touched |
| A new copy isn't on air everywhere within 60 s | Handover abandoned; the old copy never stopped. That stream is offered again after the others |
| New worker dies before a handover completes | The old worker drops the stale claim after 15 s and keeps the stream; the script tells the old worker to stop draining |
| Old worker dies mid-handover | The new copy (already on YouTube's backup ingest) takes over through the normal 45 s takeover, without reconnecting |
| Old worker stalls after handing over | Its next pass sees the stream isn't its own and stops its copy: never two owners |
| New worker dies after taking streams | The draining old worker still rescues orphaned streams (normal takeover) |
| Streams still not moved after the time limit (90 s per stream, at least 5 min) | Script stops the old worker anyway; it lets go of them and the new worker claims them within seconds (a short reconnect for those only); the workflow fails so someone looks |
| Running worker predates the handover, or no token | Script changes nothing and says to use method `restart` |

Not proven by tests, only on production: **YouTube's own failover between the primary and backup
ingest** (that the broadcast doesn't end and viewers see no gap or only a short repeat), and the move
back to the primary. Before relying on it, run one deploy while watching a private test stream on
YouTube Studio (stream health and the watch page), with a second destination on another platform.

**Memory.** During a deploy two worker JVMs run side by side for a few minutes (`-Xmx512m` each, about
700 MB resident each) plus, for each stream being handed over, a second encoder (one at a time). On
the 7.8 GB droplet, with the web app (two during a web deploy, OPS-4), PostgreSQL and MediaMTX, check
`free -m` before the first handover deploy; the script refuses to start the second worker when less
than 1.2 GB is available (`MIN_FREE_MB`). Don't run a web deploy and a worker deploy at the same time.

**CPU.** One extra encode for about 15–30 s per stream (none for streams in copy mode beyond a stream
copy). Handovers ignore the busy-CPU claim gate, since the streams have to move.

**Shared disk.** Both slots use `/opt/afklive/worker/data/worker` (the media cache and per-run work
folders): downloads in progress are named per process, so neither deletes the other's.

### One-time server setup (do this once, by hand; then deploy this release with method `restart`)

The release that brings the handover must itself be deployed the old way (its predecessor can't hand
over). Steps, as root on the droplet:

```sh
# 1. A token the deploy script and the workers share
echo "WORKER_CONTROL_TOKEN=$(openssl rand -hex 32)" >> /opt/afklive/afklive.env

# 2. Slot units and their ports
cp afklive-scripts/systemd/afklive-worker@.service /etc/systemd/system/
echo "WORKER_HTTP_PORT=8081" > /opt/afklive/worker-slot-a.env
echo "WORKER_HTTP_PORT=8082" > /opt/afklive/worker-slot-b.env
mkdir -p /opt/afklive/worker/slots/a /opt/afklive/worker/slots/b
systemctl daemon-reload

# 3. The deploy script (root-owned; the deploy user may run only this with sudo)
mkdir -p /opt/afklive/scripts
install -o root -g root -m 0755 afklive-scripts/worker-handover-deploy.sh /opt/afklive/scripts/
echo "DEPLOY_USER ALL=(root) NOPASSWD: /opt/afklive/scripts/worker-handover-deploy.sh" > /etc/sudoers.d/afklive-worker-deploy
chmod 0440 /etc/sudoers.d/afklive-worker-deploy   # replace DEPLOY_USER with the user in the DO_USER secret

# 4. Where the workflow uploads the new jar
mkdir -p /opt/afklive/worker/incoming && chown DEPLOY_USER /opt/afklive/worker/incoming
```

Then run **Deploy stream worker** with method `restart` (the last interrupting worker deploy: the
running `afklive-worker.service` restarts on this release). From the next deploy on, use `handover`:
the first one moves the streams from `afklive-worker.service` to slot b and disables the old unit;
after that deploys alternate between slots a and b. Re-run step 3 whenever the script changes.

Useful: `sudo /opt/afklive/scripts/worker-handover-deploy.sh --status` (which slot runs and what it
owns); `journalctl -u afklive-worker@b -f`. To deploy a specific commit (or roll back), give its SHA
in the workflow's `ref` input; it is built, tested and handed over the same way. Worker and web deploys
share one GitHub concurrency group (`production-deploy`), so they never run at the same time.

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
`afklive.env` can't accidentally override them. Remote workers (FO-8) get their own version from
`afklive-scripts/provision-remote-worker.sh`: the database and Loki over the WireGuard tunnel, the local DNS
resolver, nothing else private (see "Worker pools").

## Worker pools: extra workers on other providers (FO-8)

**Extra workers can run on other providers (Oracle Cloud's Always Free ARM server: 4 cores, 24 GB, 10 TB
of traffic a month; Hetzner: 20 TB included) and take the 24/7 playlists, where traffic is cheap; phone
streams stay on the main server, next to the ingest server.** With the defaults (one pool, `main`, no
preference) nothing changes: every worker takes every stream, as before.

**How streams are placed.** Every worker belongs to a pool (`STREAMING_WORKER_POOL`) and reports it in
`stream_workers.pool`. Every run records its kind in `stream_jobs.placement`:

| Placement | What it is | Who may run it |
| --- | --- | --- |
| `PHONE` | Reads a phone's feed from the ingest server (MediaMTX) | Only pools in `STREAMING_PHONE_POOLS` (default `main`) |
| (none) | A run created by an older web version, until its worker records the kind | Same as `PHONE` |
| `PLAYOUT` | A playlist that can play by copy (FO-2: nothing drawn or mixed live) | Any pool; prefers its pool in `STREAMING_POOL_PREFERENCE` |
| `PLAYLIST` | A playlist that needs the studio (overlays, music bed, scenes) | Same |
| `OTHER` | A classic single-file run | Same |

The web app sets the placement when it creates a run; the owning worker re-derives it from the run's spec
once a minute (live edits can change it) and fills it in for runs created before pools existed.

The rules are part of the claim itself (`StreamJobRepository.claimFor`, `takeOverFor`, `claimHandoverFor`:
one conditional update, as before), so a stale lookup can never claim a run its worker mustn't run, and two
workers never both get one:

- **Hard rule:** a worker whose pool isn't in `STREAMING_PHONE_POOLS` never claims, takes over or accepts a
  handover of a `PHONE` run or one without a placement, however long it has waited.
- **Preference:** a worker outside the preferred pool leaves a preferred run alone while a worker of that
  pool is available: reported in the last 30 s, not draining, a free slot (`streams < capacity`) and CPU
  below its claim threshold. If the pool is full, busy, draining or absent, it claims at once; if the
  preferred pool hasn't taken the run within 30 s, anyone may. Applies to new runs and to handover offers
  (so a remote host's deploy hands its streams to its own new version, and a main-server deploy can move
  24/7 playlists to the playout pool). Orphans (a worker died) are rescued by any allowed pool at once.

Set `STREAMING_PHONE_POOLS` and `STREAMING_POOL_PREFERENCE` to the **same values on every worker**
(each worker applies its own copy of the rules). Admin > Servers shows each worker's pool; Admin > Live
shows the pool of the worker running each stream.

### What a remote worker needs

| Need | How | Notes |
| --- | --- | --- |
| The database | **WireGuard tunnel** to the main server (below). PostgreSQL listens on the tunnel address only | Alternative: PostgreSQL with TLS (`hostssl`, `scram-sha-256`) and a firewall allow-list of the worker's IP, `SPRING_DATASOURCE_URL=jdbc:postgresql://HOST:5432/afklive?sslmode=verify-full&reWriteBatchedInserts=true`. More moving parts and an open port; prefer WireGuard |
| Library files | `STORAGE_TYPE=s3` with DigitalOcean Spaces or Cloudflare R2 (`DO_SPACES_*`); `MediaCache` fetches what a run needs | A remote worker can't read the main server's local disk: **with `STORAGE_TYPE=local` don't run remote workers** |
| Stream keys | `APP_ENCRYPTION_KEY` (and `APP_ENCRYPTION_OLD_KEYS`) as on the main server | It decrypts every stream key it runs: the remote server must be as trusted as the main one (SSH keys only, unattended upgrades on) |
| Platforms | Outbound RTMP/RTMPS/SRT to the internet | The egress firewall below keeps it off private and metadata addresses |
| Phone ingest | Not needed: remote pools don't run phone streams | To let a pool run them, add it to `STREAMING_PHONE_POOLS` and give it a read address over the tunnel (`INGEST_READ_URL=rtmp://10.77.0.1:1935/...`, `INGEST_READ_ALLOWED` on the web side incl. `10.77.0.0/24`). Latency to India matters for phones |
| Logs | Loki over the tunnel (`LOKI_URL=http://10.77.0.1:3100/...`), or drop `loki` from the unit's profiles | Loki on the main server must listen on the tunnel address too |
| Deploy token | Its own `WORKER_CONTROL_TOKEN` (generated by the script; used only on that host) | |

The same `APP_STREAMING_X264PRESET` on every worker (profile copies are made for that preset). Copy mode
(`APP_STREAMING_COPYMODE`) is per worker: turn it on on the playout pool after a soak there.

**ARM (arm64).** The worker is pure Java (no native libraries; Spring Boot, the PostgreSQL driver and the
AWS SDK are all JVM code) plus FFmpeg as a separate process. It uses only `libx264` and FFmpeg's built-in
filters and formats, no hardware encoders or x86-only flags, and passes audio as explicit `s16le`, so
Ubuntu's own `ffmpeg` and `openjdk-21-jre-headless` on arm64 run it unchanged. Two things to watch: the
app prefers `./bin/ffmpeg` in its working directory over the system one, so never copy a static x86 FFmpeg
there (the provisioning script warns); and x264 on an Ampere A1 core is slower than on a modern x86 core, so
size `STREAMING_WORKER_MAX_STREAMS` from a soak on that machine (`afklive-scripts/soak.sh`) and use the ARM
pool for copy-mode playout, where CPU barely matters. CI builds and tests on x86 only; arm64 is proved by the
soak on the first ARM worker.

### Add an Oracle or Hetzner worker (step by step)

On the **main server** (as root), once: the tunnel's server side.

```sh
apt-get install -y wireguard-tools
umask 077; wg genkey > /etc/wireguard/afklive.key; wg pubkey < /etc/wireguard/afklive.key   # note the public key
cat > /etc/wireguard/wg-afklive.conf <<EOF
[Interface]
Address = 10.77.0.1/24
ListenPort = 51820
PrivateKey = $(cat /etc/wireguard/afklive.key)
EOF
systemctl enable --now wg-quick@wg-afklive
ufw allow 51820/udp            # better: from the worker's address only: ufw allow from WORKER_IP to any port 51820 proto udp
# PostgreSQL: also listen on the tunnel, and only the tunnel's peers may log in (password as for the web app)
#   postgresql.conf: listen_addresses = 'localhost,10.77.0.1'
#   pg_hba.conf:     host afklive afklive 10.77.0.0/24 scram-sha-256
systemctl restart postgresql   # a second or two; web and workers reconnect on their own
# Keep 5432 closed to the internet (ufw default deny); the tunnel is the only way in.
```

On the **new server** (Ubuntu 24.04, arm64 or amd64), with a checkout of `afklive-scripts`:

```sh
sudo ./provision-remote-worker.sh --dry-run --pool playout \
     --main-wg-endpoint MAIN_PUBLIC_IP:51820 --main-wg-pubkey 'MAIN_PUBLIC_KEY' \
     --wg-address 10.77.0.2/24 --max-streams 4 --deploy-key 'ssh-ed25519 AAAA... afklive-deploy'
# Read what it would do, then run it without --dry-run. It prints this server's WireGuard public key.
```

It installs Java 21, FFmpeg (checks it has libx264), WireGuard and nftables; creates the `afklive-worker`
user, the `deploy` user (allowed to run only the handover script with sudo), the slot units, the handover
script, `/opt/afklive/afklive.env` (pool, database over the tunnel; secrets left empty) and the worker
egress firewall (`/etc/nftables.d/afklive-worker-egress.nft`: the worker user reaches the database and Loki
over the tunnel and the local DNS resolver, nothing else private, no cloud metadata at 169.254.169.254;
platforms and object storage are public and stay allowed). Re-running keeps keys and settings.

Then:

1. **Main server:** add the peer it printed to `/etc/wireguard/wg-afklive.conf`
   (`[Peer]`, `PublicKey = ...`, `AllowedIPs = 10.77.0.2/32`) and `systemctl restart wg-quick@wg-afklive`.
   Check from the new server: `ping 10.77.0.1`, `pg_isready -h 10.77.0.1` (package `postgresql-client`).
2. **New server:** fill in the empty secrets in `/opt/afklive/afklive.env` from the main server's file
   (copy them over SSH; never paste them anywhere else).
3. **Every worker, main included:** set the rules and restart them (on the main server with a normal worker
   deploy): `STREAMING_PHONE_POOLS=main` and, to send 24/7 playlists to the new pool,
   `STREAMING_POOL_PREFERENCE=PLAYOUT=playout` (add `,PLAYLIST=playout` to send studio playlists too). The
   main server needs no `STREAMING_WORKER_POOL` (default `main`).
4. **GitHub secrets** (repository settings): `WORKER_HOSTS` = `deploy@NEW_SERVER_IP` (several: separated by
   spaces or new lines; `host:port` for another SSH port); `WORKER_HOSTS_KNOWN_HOSTS` = the output of
   `ssh-keyscan -H NEW_SERVER_IP`, after checking the fingerprint against the server's own
   (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on it); `WORKER_HOSTS_SSH_KEY` if the deploy key
   differs from `DO_SSH_KEY`.
5. **First deploy:** Actions > Deploy stream worker with hosts `extra` and method `restart` (nothing runs
   there yet). From then on, `main+extra` with `handover` deploys the main server and then each extra host
   in turn, each without interruption; `main` (the default) leaves the extra hosts alone.
6. Watch Admin > Servers: the new worker appears with "Pool playout"; new 24/7 playlists start there, phone
   streams never do. Before moving many streams, run the soak on it (`afklive-scripts/soak.sh`) and set
   `STREAMING_WORKER_MAX_STREAMS` from the result.

**Costs.** Nothing changes until a remote worker is added. Oracle Always Free: no charge (Oracle may
reclaim an Always Free server it considers idle; keep it busy or use a paid shape). Hetzner ARM (CAX21,
4 vCPU / 8 GB, 20 TB included): a few euros a month (check the current price). Add the bill to
`APP_MONTHLY_SERVER_COST` and the included traffic to `APP_INCLUDED_TRANSFER_GB` (FO-3/FO-5 count all
servers together). WireGuard adds no cost and about 60 bytes per packet of database traffic only (stream
traffic goes straight to the platforms).

**Remove a remote worker:** on it, ask the running slot to drain, so its streams move to other workers one
at a time without interruption: `curl -X POST -H "X-Worker-Token: TOKEN" http://127.0.0.1:8081/internal/worker/drain`
(token from its `afklive.env`; port 8082 for slot b; `sudo /opt/afklive/scripts/worker-handover-deploy.sh
--status` shows which slot runs and what it still owns). When it owns nothing, stop and disable its unit.
Just stopping it also works: other workers take its streams over within 45 s (a short reconnect). Then
remove it from `WORKER_HOSTS` and its peer from the main server's WireGuard config.

## While the web app restarts: the "updating" page

When no web app answers (a deploy with the old restart, a crash, both slots down), Caddy answers instead of
the browser's bare "This page isn't working, HTTP ERROR 502":

| Request | Answer (always `503`, `Retry-After: 30`, `Cache-Control: no-store`) |
| --- | --- |
| A page (`Accept` includes `text/html`) | `afklive-scripts/caddy/errors/index.html`: "AFK Live is updating, back in about a minute. Your live streams keep running." in English and Hindi. It asks `/healthz` every 5 s (slower over time, at most every 30 s) and loads the page the visitor wanted once the app answers; "Try again" checks at once; without JavaScript it reloads every 20 s |
| `/api/*` | `{"error":"updating","retryAfter":30}`. The app (`lib/api.ts`) shows "Updating, reconnecting…" at the top, keeps retrying reads for up to 3 minutes, and turns a change (POST, PUT, ...) into "AFK Live is updating. Try again in a minute." instead of repeating it on its own |
| Anything else (`/healthz`, scripts, images) | A line of plain text |

Only Caddy's own errors land here (`handle_errors 502 503 504`: nothing listening, no healthy slot, upstream
timeout). Whatever the app answers itself passes through unchanged, its own 503s included. "Your live streams
keep running" is true because the stream workers are separate processes (`afklive-worker@a/b`) that a web
restart doesn't touch; on a single-process install (`STREAMING_ROLE=all`) streams reconnect within seconds.

The page is one self-contained file (inline CSS and script, the logo inlined by `afklive-ui/brand/build.py`,
no requests to anywhere: the app is down when it shows). `CaddyUpdatingPageTest` checks the Caddyfiles, the
workflow and the page, and with `caddy` on PATH (or `CADDY_BIN`) validates the files and runs the block for real.
Every web deploy uploads the page to `/opt/afklive/incoming/errors/` and swaps it into `/opt/afklive/errors/`
before the new version starts, so page changes ship with the next web deploy. Until the one-time setup below is
done, those two steps only warn.

### One-time server setup

The same `handle_errors` block is in both Caddyfiles: `afklive-scripts/caddy/Caddyfile.single-upstream` is for
the server as it runs today (one web app on port 8080, before the OPS-4 setup); `afklive-scripts/caddy/Caddyfile`
(two slots) already has it, so the OPS-4 setup brings it along. As root (`sudo -i`), from a checkout of this
release:

```sh
# 1. Where the page lives. The deploy user (DO_USER secret; "deploy" here) writes it, Caddy only reads it.
install -d -o deploy -g deploy -m 2775 /opt/afklive/incoming/errors
install -d -o deploy -g deploy -m 0755 /opt/afklive/errors
install -o deploy -g deploy -m 0644 afklive-scripts/caddy/errors/index.html /opt/afklive/errors/index.html
sudo -u caddy head -c 15 /opt/afklive/errors/index.html && echo " <- Caddy can read it"

# 2. Caddy. Keep a copy, install the file, check that only the new block and its import were added (keep any
#    other line your file had, e.g. encode or request_body), then validate and reload (no dropped connections).
cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-updating-page
cp afklive-scripts/caddy/Caddyfile.single-upstream /etc/caddy/Caddyfile
diff /etc/caddy/Caddyfile.before-updating-page /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy

# 3. Check: the site still answers as before
curl -sI https://afklive.in/ | head -1
```

The next web deploy publishes the page itself ("Publish updating page" says so in its log). To undo: restore
`/etc/caddy/Caddyfile.before-updating-page` and `systemctl reload caddy`; the directories can stay.

## Secrets from GitHub (OPS-7)

Integration secrets (Google sign-in, Anthropic, push, Turnstile, PhonePe, ZeptoMail, object storage) are kept as **repository secrets** in GitHub (Settings → Secrets and variables → Actions). Every web
deploy runs `afklive-scripts/sync-secrets.sh` on the server before the new version starts: it writes the secrets
that are set into `/opt/afklive/afklive.env`, replacing a key that is there and adding one that isn't. It keeps
every other line and the file's mode, and leaves a copy in `afklive.env.bak`.

- **Change a secret:** update it in GitHub, then run **Afklive-web CI & CD** (Actions → Run workflow). That is a
  zero-downtime web deploy; it doesn't touch running streams. The main server's worker reads the file on its
  own next deploy. Remote workers (FO-8) keep their own `afklive.env`.
- **Not set in GitHub = unchanged on the server.** An empty or missing secret never wipes a value. To remove
  one, delete the line on the server.
- **Allowed keys only** (the list at the top of the script, mirrored in the workflow; `SyncSecretsScriptTest`
  checks they match). Server-generated or data-critical values stay on the server only: `APP_ENCRYPTION_KEY`,
  `POSTGRES_PASSWORD`, `REMEMBER_ME_KEY`, `WORKER_CONTROL_TOKEN`, `INGEST_*`. `GEMINI_API_KEY` and `SMTP_*` are
  left out for now because GitHub holds older copies; add them to both lists once those are confirmed current.
- Values go over stdin and are never printed; the log names the keys that changed. A value with a single quote
  or a line break is refused (nothing written).

### One-time server setup

```sh
sudo install -o root -g root -m 0755 afklive-scripts/sync-secrets.sh /opt/afklive/scripts/
echo "DEPLOY_USER ALL=(root) NOPASSWD: /opt/afklive/scripts/sync-secrets.sh" | sudo tee /etc/sudoers.d/afklive-sync-secrets
sudo chmod 0440 /etc/sudoers.d/afklive-sync-secrets && sudo visudo -c   # DEPLOY_USER: the user in the DO_USER secret
```

Until this is done, the deploy's "Sync secrets" step only warns and the server's file is used as it is.

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

Go live (GL-1) first tests each enabled destination that hasn't passed a test in the last 24 hours with its
current key (`POST /api/streams/{id}/destination-checks`, then poll the `GET`); a failure blocks with the reason,
"Fix key" and "Go live anyway". A test never runs against a key a live run uses: the check skips it, and a running
test stops within a second when a run on its key goes live. Scheduled starts don't run the test.

## Billing

Plans are priced per concurrent stream (Free, Creator, Pro, Agency). Defaults live in `PlanType`; the
live values, prices included, are rows in `plan_configs` that an admin edits in Admin > Plans
(`/studio/admin/plans`) without a deploy. An edit needs a reason, is validated (prices never negative,
Free stays free, a yearly price at most 12 monthly ones, sensible limits), lands in `admin_actions`
and applies at once: prices for every checkout and renewal from then on, limits for new sign-ups and
for anyone without larger recorded terms (see grandfathering below). When a release changes the
defaults, bump `PlanConfig.CURRENT_DEFAULTS` and the rows are refreshed at startup (this overwrites
admin edits).

### Grandfathering: paying subscribers keep what they bought (BI-10)

- When a plan is paid for, the limits in effect (streams, destinations, top quality and frame rate,
  storage, monthly hours, streaming data allowance, watermark) are recorded on the subscription
  (`subscriptions.terms_*`). Every limit check (`PlanService.limitsFor`: go-live, destinations,
  quality, storage, hours, fair use, Billing and `/api/me`) uses, field by field, the more generous of
  these and the plan's current `plan_configs` row. So lowering a plan's limits never reduces a paying
  subscriber's, and raising them reaches everyone.
- Renewing (or switching cycle on) the same plan keeps the recorded terms. An upgrade records the new
  plan's limits but keeps anything the old terms gave that is more. A downgrade at the period end, or a
  new purchase after the plan lapsed to Free, records the plan as sold then. An admin putting someone
  on a different plan records that plan's current limits.
- Changeset 179 recorded today's limits on every subscription that wasn't canceled. Accounts on a paid
  plan with no subscription record (none expected) and Free accounts are not grandfathered.
- **Limits for new sign-ups** are one explicit step: Admin > Plans > "Limits for new sign-ups" shows,
  per plan, the current and recommended limits, how many paying subscribers keep theirs and who would
  be affected; "Apply recommended limits" (reason required, audited as `PLAN_LIMITS_NEW_SIGNUPS` plus a
  `PLAN_CONFIG` row per plan) first records terms on any live subscription that has none, then updates
  `plan_configs`. Recommended (owner decision 2026-10-28): Creator 1 stream, 1 destination, 1080p30,
  2,000 GB a month; Pro 3 streams, 2 destinations each, 1080p60, 6,000 GB; Agency 10 streams,
  2 destinations each, 1080p60 (no 4K), 15,000 GB; Free unchanged with 50 GB. Storage, hours and
  prices stay. Nothing is applied by a deploy. After applying, update `site/src/static-plans.ts` (the
  no-JavaScript pricing cards) in the next release.
- A fair use allowance set on a plan reaches grandfathered subscribers only if it was set before their
  terms were recorded (their recorded "no allowance" is more generous). To put one on them, use the
  data allowance or reset actions below.
- **One subscriber's limits** (Admin > Users > a user > "Plan limits", `/api/admin/users/{id}/terms`):
  the recorded terms ("Kept from their purchase on …"), the plan's limits today and what is in effect.
  Every action needs a reason and lands in `admin_actions` with before and after:
  - *Raise limits* (`TERMS_RAISE`): streams, destinations, top quality (e.g. 4K), frame rate, storage,
    hours, no watermark. Only upwards; it stays through renewals of the same plan and carries into an
    upgrade.
  - *Reset to plan* (`TERMS_RESET`): replaces the record with the plan's current limits. This is the only
    way to take a grandfathered limit away; when it does, the dialog lists what goes and the API refuses
    without `confirm: true`. Streams already on air are not stopped.
  - *Data allowance* (`TERMS_DATA_ALLOWANCE`): sets the subscriber's own monthly GB, or clears it (no
    limit of their own). They get the larger of it and the plan's, so it can't go below the plan; turning
    "no limit" into a number needs confirmation. A one-off top-up for this month is still "Grant
    streaming data".
  - Free accounts and accounts without a live subscription have no recorded terms (the panel says why).
- **Who is grandfathered** (Admin > Billing > "Subscribers on older, more generous limits",
  `/api/admin/grandfathered?plan=PRO`): live subscriptions whose recorded terms give more than their
  plan does today, with what is more, filterable by plan (first 500 rows, counts per plan).

### Launch offers (BI-9)

- Admin > Plans > "Launch offers": name, percent off (1 to 50, default 10), discounted monthly payments
  (default 3; a yearly purchase gets it once, on the first year), plans, a real start and last day (at
  most 92 days), automatic and/or a code (letters and digits, never reused). One automatic offer at a
  time. Creating, editing and ending are audited (`OFFER_CREATE`, `OFFER_EDIT`, `OFFER_END`, reason
  required).
- Editing (`POST /api/admin/offers/{id}`, only fields sent change): the name and code at any time; the
  last day earlier or later but never in the past (to stop now, "End now"), still at most 92 days from
  the start and not overlapping another automatic offer; the start only before it starts. The discount,
  months, plans, first-year and automatic settings only while nobody has paid with it and no checkout
  with it was started: after that they're what customers were promised (the API answers 409 with why,
  and the dialog shows it). Ended offers stay ended: create a new one.
- Results (Admin > Billing > an offer > "Results", `/api/admin/offers/{id}/results`, a handful of
  aggregate queries): accounts that took it by plan and cycle; the discount given with GST and without
  (the taxable value, as on the invoices; refunded payments left out); who is in a discounted period now
  and who still has discounted renewals to come; of those who used it up, how many renewed at the full
  price or upgraded; and of the accounts created while it ran, how many took it (conversion) and how
  many paid for any plan.
- Who: accounts that never had a plan (no subscription row, no plan payment, no earlier offer), once
  per account (`offer_redemptions`, keyed by user: two payments racing both count against the same
  offer). Discounted renewals need the same plan and cycle; an upgrade is at the list price and ends
  the remaining discounted renewals. Renewals already earned are honoured after the offer closes or is
  ended early.
- Math (paise): offer price = list price × (100 − percent) / 100, rounded down to the whole rupee (USD
  keeps cents), so the discount is never less than advertised. The payment order is for the offer
  price; GST is on the price paid. The invoice shows the list price's value before GST, "Less: <offer>
  (10% off)", the taxable value after the discount, then CGST/SGST or IGST (CGST Act s. 15(3)(a)).
  Payments record `offer_id`, `list_amount`, `discount_amount`; the subscription records
  `period_price` so an upgrade credits what was actually paid.
- Display: `/api/billing/offer?currency=INR` (public; 204 when none; `eligible` for a signed-in
  account) feeds the pricing page (site, built at build time: the static cards show the plain list
  prices and the script adds the offer when one runs) and the app's Billing. Structured data keeps the
  list price (the offer isn't available to everyone).
- Compliance (CCPA Guidelines for Prevention and Regulation of Dark Patterns, 2023): the struck price is
  the real current list price; the offer has a real end date shown as a date (no countdown, no "only
  today"); every rupee price says "incl. GST" and the quote shows the full amount, the discount, the
  GST and the renewal price before payment (no drip pricing).

| | Monthly list | Offer (first 3 months) | GST in offer price | Yearly list | Offer (first year) |
|---|---|---|---|---|---|
| Creator | ₹499 | ₹449 | ₹68.49 (taxable ₹380.51) | ₹4,990 | ₹4,491 |
| Pro | ₹1,499 | ₹1,349 | ₹205.78 (taxable ₹1,143.22) | ₹14,990 | ₹13,491 |
| Agency | ₹3,999 | ₹3,599 | ₹549.00 (taxable ₹3,050.00) | ₹39,990 | ₹35,991 |

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
| `APP_GRIEVANCE_OFFICER_NAME` | Grievance officer's name, shown on `/contact` |
| `APP_GRIEVANCE_OFFICER_EMAIL` | Grievance officer's email, shown on `/contact` |

The public `/contact` page shows the seller name, address and GSTIN above and the grievance officer,
filled in by the web app when the page is served (`ContactPage`), so no site rebuild is needed after
changing them (a restart is). Anything empty, malformed or still a placeholder (`XXXX`, `TODO`,
`example.com`) is left off the page; without an officer, grievances go to support@afklive.in, and the
web app logs a warning at startup naming what's missing. The cancellation and refund policy
(`/refund-policy`) describes the rules above; change both together.

## Object storage

Library files, thumbnails and prepared copies live in one bucket (`STORAGE_TYPE=s3`) or, on a single
dev or small server, on disk (`STORAGE_TYPE=local`, `data/storage/` under the unit's working
directory). Any S3-compatible store works; **Cloudflare R2** is the default choice because it charges
nothing for egress (workers download every file they play; browsers read previews) and its free tier
covers 10 GB-month of storage, 1 million writes and 10 million reads a month (then $0.015 per GB-month).

| Variable | Default | Notes |
| --- | --- | --- |
| `STORAGE_TYPE` | `local` | `s3` for any bucket (R2 included) |
| `STORAGE_ENDPOINT` | `DO_SPACES_ENDPOINT` | R2: `https://<accountid>.r2.cloudflarestorage.com` (no bucket in it) |
| `STORAGE_REGION` | `DO_SPACES_REGION`, then `auto` on R2 / `us-east-1` elsewhere | R2 only accepts `auto` |
| `STORAGE_BUCKET` | `DO_SPACES_BUCKET` | |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | `DO_SPACES_KEY` / `DO_SPACES_SECRET` | |
| `STORAGE_PATH_STYLE` | `true` on R2, `false` elsewhere | `https://host/bucket/key` instead of `https://bucket.host/key` |

The `DO_SPACES_*` names keep working, so an existing server needs no change until it moves (a
`STORAGE_*` variable that is set, even to an empty value, wins over its `DO_SPACES_*` fallback). Web
instances, workers and the optimizer Lambda read the same names. Objects are always private: the app
sends no ACL headers (R2 has no ACLs) and gives out only presigned URLs (1 hour) for previews, probing
and browser part uploads. The AWS SDK is 2.21, which sends no CRC32 "flexible checksum" headers;
**before upgrading it to 2.30 or later**, set `requestChecksumCalculation(WHEN_REQUIRED)` and
`responseChecksumValidation(WHEN_REQUIRED)` on the client and presigner (`S3StorageService`), or
presigned part uploads start carrying checksum parameters a browser can't satisfy.
`S3StorageServiceR2Test` pins the request shapes.

### Moving to Cloudflare R2

1. **Bucket.** Cloudflare dashboard → R2 → Create bucket, e.g. `afklive-media`, location hint
   Asia-Pacific (close to the servers and Indian creators). Leave public access off.
2. **CORS** (bucket → Settings → CORS policy), so browsers can PUT upload parts and read the ETag:
   ```json
   [{"AllowedOrigins": ["https://afklive.in"], "AllowedMethods": ["PUT", "GET", "HEAD"],
     "AllowedHeaders": ["*"], "ExposeHeaders": ["ETag"], "MaxAgeSeconds": 3600}]
   ```
3. **Lifecycle** (bucket → Settings → Object lifecycle rules): abort incomplete multipart uploads after
   2 days.
4. **Token.** R2 → Manage R2 API tokens → Create API token: permission *Object Read & Write*, applied
   to this bucket only. Copy the Access Key ID, the Secret Access Key (shown once) and the S3 endpoint
   `https://<accountid>.r2.cloudflarestorage.com`. Don't use an account-wide token.
5. **Copy what's there today** (the app keeps working on the old store meanwhile). Install rclone on
   the server (`sudo apt-get install -y rclone`, or `curl https://rclone.org/install.sh | sudo bash`
   for a current version) and define both ends with environment variables, so no keys land in a file:
   ```sh
   set +o history   # keep the keys out of shell history
   export RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare RCLONE_CONFIG_R2_REGION=auto \
     RCLONE_CONFIG_R2_ENDPOINT=https://<accountid>.r2.cloudflarestorage.com RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true \
     RCLONE_CONFIG_R2_ACCESS_KEY_ID=... RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=...
   # From DigitalOcean Spaces:
   export RCLONE_CONFIG_OLD_TYPE=s3 RCLONE_CONFIG_OLD_PROVIDER=DigitalOcean \
     RCLONE_CONFIG_OLD_ENDPOINT=blr1.digitaloceanspaces.com RCLONE_CONFIG_OLD_ACCESS_KEY_ID=... RCLONE_CONFIG_OLD_SECRET_ACCESS_KEY=...
   rclone copy old:<spaces-bucket> r2:afklive-media --transfers 8 --checksum --progress
   rclone check old:<spaces-bucket> r2:afklive-media --one-way     # must report 0 differences
   # Or from local disk (STORAGE_TYPE=local), skipping half-finished uploads:
   rclone copy /opt/afklive/data/storage r2:afklive-media --exclude '.uploads/**' --transfers 8 --progress
   rclone check /opt/afklive/data/storage r2:afklive-media --one-way --exclude '.uploads/**'
   ```
   Storage keys are file names (`<uuid>_<name>`), the same in every store, so nothing in the database
   changes. `copy` never deletes; run it as often as you like.
6. **Switch** (no downtime; streams keep running):
   - [ ] Run the `rclone copy` again right before switching (it copies only what's new).
   - [ ] In `/opt/afklive/afklive.env` set `STORAGE_TYPE=s3` and the `STORAGE_*` values above (the
         file stays root-owned, mode 600). The `DO_SPACES_*` lines can stay; `STORAGE_*` wins.
   - [ ] Deploy the stream workers (Actions → Deploy stream worker): the handover starts them on the
         new settings without a break. Then redeploy the web app (re-run the last web deploy, method
         zero-downtime). Do both back to back.
   - [ ] Straight after, run the `rclone copy` one last time: it picks up anything uploaded to the old
         store between the two switches. Until then a file uploaded in those few minutes can't be read
         by the processes already switched; nothing else is affected.
   - [ ] Check: upload a file in the Library (it reaches Ready with a thumbnail), open an old file's
         preview, start a test stream with an old file, and look at the logs for storage errors.
   - [ ] Uploads in progress at the switch can't resume (their parts are in the old store); they
         restart from the beginning. Do it at a quiet hour.
   - [ ] Keep the old bucket (or folder) untouched for two weeks as a fallback, then delete it.

   Reading from both stores at once isn't needed: everything is copied before the switch and the final
   copy closes the gap. Rolling back is the same switch in reverse (copy R2 → old store first).
7. If the optimizer Lambda is in use, give it the same `STORAGE_*` variables.

## Database backups

Every night `afklive-db-backup.timer` runs `afklive-scripts/db-backup.sh`: `pg_dump` in custom format,
checked with `pg_restore --list`, uploaded to its own R2 bucket with rclone, then pruned to the newest
**7 daily and 4 weekly** (Sunday) backups, only after that night's upload succeeded. A failure exits
non-zero, writes `db-backup: FAILED: <why>` to the journal and leaves the unit failed
(`systemctl --failed`); with `BACKUP_PING_URL` set, a dead man's switch (e.g. healthchecks.io's free
plan) also emails when a night fails or never runs. Keys come from the environment and are never
printed or put on a command line. Eleven dumps of a database this size fit easily in R2's free 10 GB.

Setup (once, on the database server):

```sh
# 1. Tools. pg_dump must be the database server's major version or newer.
sudo apt-get install -y postgresql-client rclone
# 2. In Cloudflare: R2 → Create bucket "afklive-backups" (no public access), and an API token with
#    Object Read & Write on that bucket only. Separate from the media bucket and its token, so the
#    app's credentials can't touch backups.
# 3. Settings, root-only (systemd reads them as root before starting the service as afklive)
sudo install -m 600 -o root -g root /dev/null /opt/afklive/backup.env
sudoedit /opt/afklive/backup.env
#   BACKUP_S3_ENDPOINT=https://<accountid>.r2.cloudflarestorage.com
#   BACKUP_S3_BUCKET=afklive-backups
#   BACKUP_S3_ACCESS_KEY=...
#   BACKUP_S3_SECRET_KEY=...
#   BACKUP_PING_URL=https://hc-ping.com/<uuid>     (optional)
# 4. Scripts and units
sudo install -d -m 755 /opt/afklive/bin
sudo install -m 755 afklive-scripts/db-backup.sh afklive-scripts/db-restore.sh /opt/afklive/bin/
sudo install -m 644 afklive-scripts/systemd/afklive-db-backup.service afklive-scripts/systemd/afklive-db-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now afklive-db-backup.timer
# 5. A first run now, and check it
sudo systemctl start afklive-db-backup.service; journalctl -u afklive-db-backup -n 20 --no-pager
systemctl list-timers afklive-db-backup.timer
```

It runs at 21:30 UTC (03:00 IST) with `Nice=10` and idle I/O priority, so streams on the same server
aren't disturbed. Retention and folder: `BACKUP_KEEP_DAILY`, `BACKUP_KEEP_WEEKLY`, `BACKUP_WEEKLY_DAY`,
`BACKUP_PREFIX` (default `db`). Without `BACKUP_S3_*` the script falls back to `STORAGE_*`, then
`DO_SPACES_*`. The old `backup_db.sh` now just runs `db-backup.sh` (an old crontab entry keeps
working once rclone is installed); remove that crontab line once the timer is on.

### Restore drill (monthly, about 10 minutes)

A backup is only proven by restoring it. On the first Monday of the month, as root:

```sh
sudo -u postgres createdb -O afklive afklive_drill
bash -c 'set -a; . /opt/afklive/afklive.env; . /opt/afklive/backup.env; set +a; /opt/afklive/bin/db-restore.sh latest afklive_drill'
#   → "restored: N tables, M changesets, U users": compare with the live database
sudo -u postgres psql -d afklive_drill -c "select max(dateexecuted) from databasechangelog"   # last night's schema
sudo -u postgres dropdb afklive_drill
```

`db-restore.sh --list` shows every backup; one restores by name too, e.g.
`db-restore.sh afklive-2026-10-25.dump afklive_drill`. Write down the date and result where the team
sees it.

### Restoring production (disaster only)

Streams stop while the database is replaced. Stop the web app and workers
(`systemctl stop 'afklive-web@*' 'afklive-worker@*'`), then
`sudo -u postgres dropdb afklive && sudo -u postgres createdb -O afklive afklive`, run
`db-restore.sh latest afklive --into-live` the same way as the drill, and start the services again.
The script never drops or overwrites anything itself: it refuses a database that has tables, and the
live database's name without `--into-live`.

## CI checks

`.github/workflows/pr-checks.yml` runs only what a pull request can affect
(`.github/scripts/changed-areas.sh`): docs-only changes (`docs/**`, any `*.md`) build nothing; changes
only under `afklive-ui/` run the UI typecheck, tests and build (a couple of minutes, no Java or
FFmpeg); anything else runs the full `mvn -B verify`. A new push to a PR cancels the run for the old
commit. The **"PR checks passed"** job always runs and fails only if a job the change needed failed,
so it's the one check to require in branch protection (Settings → Rules → the `main` ruleset →
Require status checks to pass → "PR checks passed"); requiring the individual jobs would block
docs-only PRs, whose jobs are skipped. Deploy workflows are unchanged.

## Dependency updates (Dependabot)

`.github/dependabot.yml` checks every Monday morning (IST) for updates to Maven (the parent pom,
`afklive-web`, `afklive-optimizer`), npm (`afklive-ui`) and GitHub Actions. Minor and patch updates
come grouped, one PR per ecosystem; majors come one PR each, at most 3 open per ecosystem (2 for
Actions), so the queue stays small. Spring Boot and React (with `react-dom` and their types) major
versions are ignored: those are planned migrations on a branch with the full test run, not bot PRs.
To take one, remove its `ignore` entry or update by hand.

Every Dependabot PR runs the normal PR checks. Review the changelog of anything that touches
streaming, billing or security before merging; merging deploys to production like any merge to
`main`, so never turn on auto-merge for these.

**Owner, once**: Settings → Code security → turn on **Dependency graph**, **Dependabot alerts** and
**Dependabot security updates** (alerts for known vulnerabilities in what we ship, and a PR with the
fix as soon as one exists, outside the weekly schedule). The version updates above need nothing more
than this file being on `main`.

## Media library and uploads

Uploads are resumable (ML-1): the browser asks `/api/media/uploads` for an upload, then sends 16 MB
parts. With S3-compatible storage each part goes **straight to the bucket** with a presigned URL, so
upload traffic never touches the web servers; with local storage (dev) parts come through
`PUT /api/media/uploads/{id}/parts/{n}`. A page refresh resumes from the parts already stored. The
file's size is reserved against the plan's storage when the upload starts; uploads untouched for 24 h
are aborted and give the space back.

The bucket needs a CORS rule so browsers can PUT parts and read the ETag, and a lifecycle rule that
aborts incomplete multipart uploads after 2 days as a backstop: see "Object storage" above.

New files start as Processing. A web instance claims each one, probes it with ffprobe over a
presigned URL (ranged reads, no full download), stores a thumbnail and marks it Ready or Failed with a
plain reason. Files aren't re-encoded: the studio re-encodes at playout anyway. A claim older than
10 minutes is taken over, so a restart never leaves a file stuck. Web instances therefore need
`ffmpeg`/`ffprobe` on PATH, like workers.

## Templates and overlay fonts (GR-8)

Niche templates (`NicheTemplates`, `POST /api/streams/{id}/template`) are applied through
`LiveStreamService.setOverlays/setScenes/update/setPresentation` and `StreamScheduleService.create`, so
a live stream picks them up between frames like any edit. Template layers have ids starting `tpl-`.
Overlay text that Poppins can't draw falls back to the Noto Sans fonts bundled in the jar
(`resources/fonts`, about 1.4 MB, SIL OFL); characters no bundled font has (emoji) are left out instead
of drawn as boxes. The fonts are drawn by workers, so **workers need the new jar** for Indian-script
text; until then a worker draws those characters as boxes, exactly as before.

## Referrals (GR-18)

`?ref=CODE` on any GET sets an `afk_ref` cookie (30 days, first invite wins, HttpOnly). Email and
Google sign-ups read it and add a `referrals` row (one per new account, never self). When the invited
creator's first live is confirmed (ON-8, below), the same sweep credits the referral: a conditional update
moves it `JOINED` → `CREDITED` (sets `confirmed_live_at`, `credited_at`) and, in the same transaction,
`BillingService.addFreeDays` gives each side 30 days (a paid plan's period is extended, like an admin
extension; on Free the days go to `users.bonus_days` and join the next paid period, shown on its invoice
line) and an `admin_actions` row (`REFERRAL_CREDIT`, by `system`) records both outcomes. Both creators
get an in-app notice after it commits. A referral whose inviter or invited account is disabled waits.
`users.credit_line` (off by default) appends "Streamed 24/7 with AFK Live: <link>" only to descriptions
AFK Live already writes (the classic start flow's broadcast update); never to overlays.

After a video is Ready, the same instance checks whether it is one still picture (GR-9): six small
grey frames grabbed with a seek each (5% to 95% of the length) and compared. The result goes in
`media_assets.still_picture` (null when a frame couldn't be read, so the check never guesses). Videos
uploaded before this are measured by the media sweeper, two a minute, videos in a playlist first.

## Go-live check (GR-9)

The stream check on a stream's Overview (`/api/streams/{id}/check`) reads only what AFK Live has: the
playlist, overlays, the measured still-picture flag, the YouTube title and thumbnail the creator typed
in (`streams.youtube_title`, `thumbnail_media_id`) and replies AFK Live posted in the past
(`engagement_activity`, history only: nothing writes to it since automatic replies were removed, see below).
It never blocks going live and never calls YouTube. Thumbnail contrast is measured once per image per
web instance (in memory) from the library thumbnail. Once a week per stream that was live in the past
seven days, one web instance (conditional update on `streams.check_weekly_at`) sends an in-app notice
listing open flags; no email or push. `app.check.weekly-auto=false` turns the hourly pass off (tests).

## Title writer (AI-1, GR-10)

"Write title and description" in the stream check (`/api/streams/{id}/ai/metadata`, web only) asks the AI
model for three titles, a description and tags in English, Hindi, Tamil, Telugu, Bengali or Marathi, checks
them with the GR-9 title rules (plus "live" written in Indian scripts, and the language's script: when some
titles are in it, the ones that aren't are dropped) and adds a tracklist with timestamps worked out from the
playlist. Drafts only: nothing is sent to YouTube. The same model writes the older one-line drafts
(`/api/ai/*`, library uploads) and comment-reply drafts.

**Which model.** Claude (Anthropic's Messages API, plain HTTP with the app's timed client: 5 s connect, 25 s
read) when `ANTHROPIC_API_KEY` is set; else Gemini when `GEMINI_API_KEY` is set; else AI is off and the writer
is hidden (the endpoint answers `UNAVAILABLE`, never an error). Errors, timeouts, rate limits (429), overload
(529) and an empty credit balance count as "no answer": the creator gets a start from their own words and
the draft isn't counted. Only the status code is logged; prompts, answers and keys never are. Keys go in
headers (`x-api-key`, `x-goog-api-key`), never URLs. If Gemini is used, its key must belong to a Google Cloud
project with billing on: the free tier may use prompts for training, which the privacy policy rules out.

| Variable | Default | What it does |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | empty | Set: Claude writes (wins over Gemini) |
| `ANTHROPIC_MODEL` | `claude-haiku-5-5` | The Claude model id |
| `GEMINI_API_KEY` | empty | Used when there's no Anthropic key. Both empty: the writer is hidden |
| `GEMINI_MODEL` | `gemini-flash-lite-latest` | The Gemini model id; keep a Flash-Lite class model |
| `AI_DAILY_LIMIT_FREE` | 20 | Drafts per account per day on Free (the day is the creator's time zone) |
| `AI_DAILY_LIMIT_PAID` | 100 | The same on paid plans |
| `APP_AI_MONTHLY_BUDGET_USD` | 10 | AI stops for the rest of the UTC month once this month's spend reaches it. 0: no AI calls at all |
| `APP_AI_PRICE_INPUT_PER_MTOK` | 0.10 | US dollars per million input tokens of the model in use (Claude Haiku 5.5's price) |
| `APP_AI_PRICE_OUTPUT_PER_MTOK` | 0.50 | The same for output tokens. Set both when you switch model or provider |

Fair use is counted in `ai_usage` with one conditional upsert (`generations < limit`), so several web
instances can't let an account past it; an answer that can't be used is not counted. The same inputs
(stream, language, name, title, note, playlist, model) within 24 hours read `ai_drafts` and cost nothing.

**Monthly spending cap (FinOps).** Every call's input and output tokens, as the API reports them (`usage`,
or Gemini's `usageMetadata`), are added to `ai_spend` (UTC month, provider, model) with one upsert and priced
at the rates above. Before each call the month's sum is read from the database, so every web instance stops
at the cap; calls already in flight can overshoot it by a few cents. At the cap the writer says "Writing
titles isn't available right now" until the month ends (UTC). Admins get one alert at 80% and one at 100% per
month (in-app and email; `ai_budget_alerts (month, threshold)` is the claim, as with the transfer alerts).
Admin, Billing, "AI spend" shows the month's spend, the cap and tokens by model (`GET /api/admin/ai-spend`).
A title-writer draft is about 1,000 input and 400 output tokens: about $0.0003 with Haiku, so the default $10
is roughly 30,000 drafts a month.

**Quality check.** `TitleWriterIndicTest` runs the writer's checks over recorded answers for 30 realistic
streams (`afklive-web/src/test/resources/ai-eval/`). To see what the real model writes for them, run
`ANTHROPIC_API_KEY=... afklive-scripts/ai-eval.sh` (Maven profile `ai-eval`, not part of any build; 30 calls,
about a cent) and read `afklive-web/target/ai-eval/report.md`.

## Automatic replies removed (AI-3)

AFK Live never posts in YouTube chat, replies to comments or deletes comments by itself. YouTube's spam
policy and API Developer Policies (III.I.2, III.E.3.d) forbid automated chat and comments without the
creator's consent for each action. `EngagementService` (an AI reply to every live chat message, and
comment replies and deletes by sentiment) is gone, with the `/api/engagement/settings` endpoints and the
per-run auto-reply flag. What remains: reading comments, reply drafts (`/api/engagement/suggest`, posts
nothing), a reply the creator writes and sends (`POST /api/comments/{id}/reply`), the history of replies
posted before (`/api/engagement/activity`, `/revert`) and the GR-9 repeated-reply flag, which now only
explains (no fix). JobRunr ids `live-engagement-job` and `general-engagement-job` are still deleted at
startup.

Expand-then-contract: the release before this one maps these columns, so they stay for now and nothing
writes them. **Contract step** (a later release, once no instance runs the previous version): one
changeset that drops `stream_jobs.auto_reply_enabled`, `stream_jobs.last_page_token`,
`stream_jobs.live_chat_id`, and `user_settings.auto_reply_enabled`,
`delete_negative_comments`, `auto_reply_unreplied_enabled`, `auto_reply_unreplied_message`. Keep
`engagement_activity` while GR-9 or the activity history reads it; `processed_comments` can go then too.

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

## Uptime monitoring from outside (`/healthz`)

`/status` and the in-app alerts run on our own servers, so they can't tell anyone when those servers
are gone. An outside monitor calls a public health check instead:

| Address | 200 `ok` when | 503 `unavailable` when |
| --- | --- | --- |
| `GET /healthz` | the web app answers and the database answers `SELECT 1` within 2 s | the database query fails or takes longer than 3 s |
| `GET /healthz?workers=1` | the above, and at least one stream server (`stream_workers`) reported in the last 2 minutes | either part fails |

It never says why (no error text, versions or counts), sends `Cache-Control: no-store` and
`X-Robots-Tag: noindex`, is not in the sitemap, needs no sign-in, creates no session and is outside
the API rate limiter (`HealthController`, `HealthzProbe`, `HealthzSecurityConfig`). Each web instance
reuses its answer for 5 s, so frequent checks cost the database almost nothing; HEAD works too. If both
deploy slots are down, Caddy answers 503 with a line of text ("While the web app restarts", above; 502 before
that setup), which a monitor also reads as down. The localhost-only
actuator probes (`/actuator/health/readiness`, used by Caddy and the deploy script) are separate and
unchanged.

### Set up the monitors (owner, once, about 10 minutes)

Free tiers as of October 2026 (check before relying on them): **Better Stack** Free has 10 monitors,
checks every 3 minutes, alerts by email and Slack (phone/SMS are paid); **UptimeRobot** Free has 50
monitors, checks every 5 minutes, alerts by email (and a few integrations), and is described as for
hobby and non-profit use, so for a paying business prefer Better Stack, or UptimeRobot Solo
(about $12/month, 60-second checks) if 1-minute checks matter. Neither free tier checks every minute,
and neither sends WhatsApp alerts; Telegram is an UptimeRobot integration on plans that include it.

Create three HTTP monitors (keyword or status-code checks; expect status 200; request timeout 10 s;
alert after 2 failed checks in a row so a deploy's handover never pages anyone):

1. **Website and database**: `https://afklive.in/healthz`. Alert: email, plus the phone app's push or
   Telegram where the plan has it.
2. **Stream servers**: `https://afklive.in/healthz?workers=1`. Same alerts. When this one is down and
   the first is up, the web app is fine but no worker is reporting: live streams are at risk; see
   "Worker deploys without interruption" and Admin > Servers.
3. **Homepage**: `https://afklive.in/` (expects 200). Catches DNS, certificate and Caddy problems that
   don't reach the app.

Optionally publish the monitor's status page as a second, independent status page. Nothing to deploy
or pay for with the free tiers; the monitors only call the public site.

## Admin: workers, cost and YouTube quota (AD-2, YT-4)

| Variable | Default | Meaning |
| --- | --- | --- |
| `APP_MONTHLY_SERVER_COST` | empty | The monthly bill for all servers, a plain number. Admin divides it by the last 30 days' stream hours (all runs, from `stream_jobs`). Empty: no estimate |
| `APP_SERVER_COST_CURRENCY` | `INR` | Currency of the number above |
| `APP_YOUTUBE_DAILY_QUOTA` | 10000 | The Google Cloud project's YouTube Data API units per day; Admin > Platform shows today's use (resets at midnight Pacific) |
| `APP_INCLUDED_TRANSFER_GB` | empty = 5000 | Outbound transfer included in the server bill each month, in GB (GiB, as DigitalOcean counts). The default is one DigitalOcean 4 vCPU / 8 GiB droplet (Basic or CPU-optimized: 5,000 GiB). DigitalOcean pools transfer across a team's droplets, so with more servers set the sum. `0`: nothing included (every GB is paid; no budget alerts) |
| `APP_TRANSFER_PRICE_PER_GB` | empty = DigitalOcean's $0.01 | Price of a GB beyond the included transfer, in `APP_SERVER_COST_CURRENCY`. Empty: 0.01 for USD, 0.85 for INR (at 85 rupees to the dollar; set it if the rate has moved), unknown for other currencies |
| `APP_TRANSFER_ALERTS_AUTO` | `true` | Web instances check the traffic budget every 10 minutes (FO-5); `false` turns the check off |
| `APP_GATEWAY_FEE_PERCENT` | empty = 2 | The payment gateway's fee, in percent of the price before GST, for the worst case per plan (fair use, below) |
| `APP_SERVER_COST_PER_STREAM_MONTH` | empty | One 24/7 stream's share of the server bill a month, in `APP_SERVER_COST_CURRENCY`, for the worst case per plan. Empty: this month's measured server cost per stream-hour × 720 (needs `APP_MONTHLY_SERVER_COST`), else unknown (traffic only) |
| `APP_FAIR_USE_AUTO` | `true` | Web instances apply plan streaming data allowances every 5 minutes (fair use, below); `false` turns the sweep off (tests) |

### Cost per stream-hour (FO-3)

Admin, Billing, "Cost per stream-hour (estimate)" works out, for the current UTC month:

```
server cost per stream-hour  = APP_MONTHLY_SERVER_COST / stream-hours projected to month end
traffic cost for the month   = max(0, GB projected to month end − included GB) × price per GB
traffic cost per stream-hour = traffic cost for the month / stream-hours projected to month end
```

- **GB sent** is the sum of bytes each destination actually sent (`stream_job_destinations.bytes_sent`,
  written by workers with the heartbeat). A run that spans two months counts in each in proportion to its
  time. Workers older than the release that measures bytes report nothing, so traffic shows as zero
  (the panel says so) until the worker batch is deployed.
- **Stream-hours** are every run's time this month (`stream_jobs`; a run without an end time ends at its
  last heartbeat).
- **Projection**: the month so far, scaled to the whole month.
- **By plan** uses each account's plan now; **by quality** uses the run's own height and frame rate.
  Server cost is shared equally per stream-hour (CPU is measured per server, not per stream; the panel
  shows the measured average CPU per stream from `worker_samples`). Traffic cost is shared by the GB each
  group sent; hovering it shows the cost per hour once the included transfer is used up.
- **Margin per plan** compares the plan's monthly price before 18% GST with its cost: for the average
  account (the plan's projected hours over its accounts) and at full use (every included hour, 24/7 for
  30 days when unlimited).

### Traffic budget alerts (FO-5)

Every 10 minutes a web instance adds up this month's GB sent. At 70% and at 90% of
`APP_INCLUDED_TRANSFER_GB`, every enabled ADMIN account gets one alert (in-app, email and push as their
alert settings allow) linking to admin Billing: once per threshold per month, across all web instances
(claimed in `transfer_alerts`). Passing both between two checks sends only the 90% one. The admin
Overview lists it under "needs attention" from 70% (red from 90%) for the rest of the month.

### Fair use: streaming data per plan (FO-5)

Each plan can include a monthly amount of **streaming data**: the GB its account's destinations send
(`plan_configs.monthly_traffic_gb`; empty = no limit, which is every plan until an admin sets one). Set it in
admin, Billing, Plans, "Streaming data a month (GB)" (a reason is required and lands in `admin_actions`). It
applies at once to everyone on the plan.

- **Usage** is `CostMeter.gbByAccount`: the sum of `stream_job_destinations.bytes_sent` of the account's runs in
  the **UTC calendar month** (like streaming hours, the cost panel and the admin traffic budget; it starts over
  at 05:30 on the 1st in India). The counter only grows and a new owner carries on from the last recorded value,
  so a handover or takeover is counted once; a run spanning two months counts in each by its time. Workers older
  than the release that measures bytes report nothing, so their runs count as zero.
- **Every 5 minutes** (`FairUseSweep`, web only) each account with a run this month on a plan with an allowance
  is checked against the allowance plus this month's grants:
  - **80%**: the creator is told (in-app, and push and email as their alert settings allow, kind "Storage, plan
    and payments"), once per step per month across slots (`traffic_notices`). Passing several steps between two
    checks sends only the highest.
  - **100%**: every live run of the account keeps its **first destination that's on**; the others are turned
    off with the same live command as a creator's own toggle (DS-5: positions never change, the worker removes
    their relays, the first destination's connection is untouched). Go-lives start with the extras in the run
    but off, and a destination turned on while capped stays off. The creator's saved choices don't change.
  - **110%**: runs started or restarted from then on go out at **720p 30** at the plan's 720p bitrate (FO-4).
    Running streams keep their quality: changing it needs a new encoder and a reconnect, which fair use never
    causes. The go-live check says what will happen before the start.
  - **Back under 100%** (an upgrade, a plan edit, an admin grant or a new month): accounts paused this month or
    last get their destinations back on within 5 minutes; a grant or an admin plan change applies at once.
- **Grants**: admin, Users, an account, "Grant streaming data": extra GB for the current UTC month only
  (`traffic_grants`, audited as `TRAFFIC_GRANT`).
- **Nothing is ever stopped** by fair use. A stream to one destination past 110% keeps streaming at its
  quality until it's restarted; that is the honest worst case, shown below.

**Worst case per plan** (admin, Billing; `GET /api/admin/costs/worst-case`), estimates:

```
GB per destination-hour = (plan's video kbps at its top quality + 128 kbps audio) × 1.04 overhead × 3600 / 8 / 2^30
stream-hours            = streams × 720 (or the plan's monthly hours when it has a limit)
worst GB                = stream-hours × destinations × GB/h, or with an allowance A that's reached:
                          A + (stream-hours − A / (destinations × GB/h)) × GB/h   (one destination per stream after A)
worst cost              = worst GB × APP_TRANSFER_PRICE_PER_GB (as if the included transfer is used up)
                          + stream-hours × server cost per stream-hour
net price               = monthly price / 1.18 × (1 − APP_GATEWAY_FEE_PERCENT / 100)
meets Gate A            = worst cost ≤ 60% of net price
```

The typical case is the average account on the plan that streamed this month, projected to month end. The panel
also shows, per plan, the largest allowance that would meet the 60% rule at the current inputs (a suggestion;
nothing is set automatically). With DigitalOcean's ₹0.85 per GB beyond the included transfer, no paid plan meets
it at 1080p 24/7 even with one destination (about 1.9 TB, ₹1,630 a month against ₹414 net for Creator): the
allowance limits extra destinations, not a single 24/7 stream, so the price per GB (FO-8) and the default
quality decide the rest.

### Prepared copies clean-up (FO-6)

Playout copies (smaller files the studio decodes) and profile copies (stream-ready files for copy mode)
are made on demand and deleted by the web sweeper (every minute, 50 at a time) when nobody has played them
for 30 days, or, never played, 30 days after they were made; failed ones go after 30 days too. The stored
file goes with the row. A copy without overlays of a video that any live run plays (its stream's items, a
schedule slot's list, or a key in the run's spec) is never deleted, because copy mode only looks its
copies up when a run starts. Copies with overlays (`PROFILE_OVERLAY`, one per overlay set) aren't spared
that way, or a stream whose logo moved would keep its old sets as long as it runs: a run playing them by
copy records a play every 6 hours (`CopyRuns.TOUCH_EVERY`), and sets nobody has played for 30 days go. A deleted copy is made again the next time a stream needs it. Copy sizes are recorded when made
(older copies are measured from storage a few a minute); admin Servers shows how many there are, their
storage by kind, and how many are due for deletion.

Worker CPU and memory are the machine's (or container's), since FFmpeg runs as child processes;
memory "used" leaves out reclaimable page cache (`MemAvailable`).

### Business numbers (Admin > Business)

`/studio/admin/business` (`BusinessMetrics`, `GET /api/admin/business`, ADMIN only) shows, from
data we already keep (no new tables): accounts and sign-ups per week; active creators per week
(accounts with a stream on air for any part of the week); stream-hours per week (counted like the
Costs panel); first go-live (`users.first_live_at`) overall and for each week's sign-ups (ever, and within 24 hours: the spec's activation measure); Free → paid
(accounts with a completed payment) and paying now by plan; MRR; churn; and retention by sign-up week
for the last 8 weeks. Weeks start Monday 00:00 UTC, months are UTC calendar months, and admin accounts
are left out. Seven queries per load, refreshed every 5 minutes while open.

- **Paying**: a subscription that is `ACTIVE` or `PAST_DUE` (grace days) on an account with at least one
  completed payment. Plans an admin gave without a payment are listed apart and left out of MRR.
- **MRR**: for each `ACTIVE` paying subscription, what its current period actually cost
  (`subscriptions.period_price`: after any launch offer discount, before upgrade credit, so an upgrade
  counts at the new plan's full price) for its cycle (yearly ÷ 12), ÷ 1.18 to take out GST for INR.
  Periods paid before `period_price` was recorded (#214) count at today's list price, and the page says
  how many. The same total at list prices is shown beside it, so the gap is what offers take off for
  now (it closes by itself as discounted renewals run out). USD plans (zero-rated exports) are shown
  separately; no exchange rate is applied. A plan set to end at the period end still counts until it
  ends; one in its grace days doesn't.
- **Churn (estimate)**: paid subscriptions that moved to Free in the month (`status = CANCELED`, by
  `updated_at`); rate = moved this month ÷ (paying now + moved this month). The subscription row is
  reused when someone pays again, so a leave-and-return within one month isn't counted.
- Accounts made before `users.created_at` existed count in the totals but in no week.

## Deleted library files (ML-6)

Creators can delete any file from the Library, one at a time (the bin on each file) or several at once,
whether or not storage is full; files a live stream is playing are refused. Every delete writes a row to
`media_deletions` in the same transaction (title, type, size, the storage keys it had, the non-live streams
it was taken out of, when it was uploaded and deleted). The file and its prepared copies are removed from
storage; the row stays for support. Admins see it on the user's admin page under "Deleted files"
(`GET /api/admin/users/{id}/deletions`).

## SEO: what the server does, and the checks to run on production

The public site (`afklive-ui/site`, served at `/`, `/hi`, `/pricing`, `/for/*`, `/help/*`, `/privacy`,
`/terms`, `/status`) is plain HTML. What keeps it crawlable:

- **One address per page.** `PublicPages` lists every public page and the built file it forwards to.
  `SiteUrlFilter` (first in the security chain, before sign-in is checked) answers `/pricing/` and
  `/help/x/` with a 301 to the address without the slash, and `/site/pricing.html`, `/site/for-lofi.html`
  (the built files) with a 301 to `/pricing`, `/for/lofi`. `/site/` itself and unknown `/site/*.html` are a
  404. Old addresses (`/pricing.html`, `/home.html`, `/features`) are 301s.
- **A real 404.** A signed-out request for an address nothing answers (`/blog`, `/Pricing`) gets the
  site's 404 page (`templates/error/404.html`, noindex), not a redirect to the sign-in page.
  `PublicNotFoundEntryPoint` still sends anything that exists (the app, the API, controllers, files
  such as `remote.html`) to sign-in.
- **The sitemap** (`/sitemap.xml`, `SitemapController`) is written from `PublicPages`, without `/login`,
  `/register` and `/status` (noindex). `lastmod` is the date of the last commit that changed each page
  (the site build writes `static/site/lastmod.json`; it needs full git history, so `web-build.yml`
  checks out with `fetch-depth: 0`; a shallow build leaves the dates out).
- **Caching** (`MvcConfig`): hashed files under `/site/assets/` and `/app/assets/` for a year
  (`immutable`); `/site/img/`, `/site/brand/` and `/favicon.ico` for a day; pages and `/site/site.css`
  (the sign-in pages' stylesheet, a stable name) `no-cache` (browsers recheck, a 304 when unchanged).
  Spring Security's `no-store` stays on everything else: signed-in pages, the API, sign-in.
- **Structured data** is written at build time from what each page shows (`site/src/seo.ts`,
  `help.py`, `use-cases.py`): Organization and WebSite on `/` and `/hi`, SoftwareApplication with the
  ₹ plans (from `static-plans.ts`) on `/` and `/pricing`, FAQPage from each page's visible FAQ (each
  question on one page only), BreadcrumbList on help and use-case pages, TechArticle on help articles.
  `site/src/seo.test.ts` checks it matches the page.

### Production checks (after a deploy that touches routing, Caddy or the site)

```sh
UA='Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)'
for u in https://afklive.in/ https://afklive.in/robots.txt https://afklive.in/sitemap.xml \
         https://www.afklive.in/pricing http://afklive.in/pricing http://www.afklive.in/ \
         https://afklive.in/pricing/ https://afklive.in/nope https://afklive.in/site/index.html \
         https://afklive.in/studio; do
  echo "== $u"; curl -sS -o /dev/null -D - -A "$UA" "$u" | grep -iE '^HTTP|^location|x-robots|cache-control|content-type'
done
# The stylesheet and a font the homepage links (hashed names change every build):
curl -s https://afklive.in/ | grep -oE '/site/assets/[^"]+\.(css|woff2)' | sort -u | head -3 | \
  while read -r a; do echo "== $a"; curl -sSI "https://afklive.in$a" | grep -iE '^HTTP|cache-control'; done
```

Expected:

| Request | Answer |
|---|---|
| `/`, `/robots.txt`, `/sitemap.xml` | 200, no `X-Robots-Tag`; `/` has `Cache-Control: no-cache, public` |
| `https://www.afklive.in/pricing`, `http://afklive.in/pricing`, `http://www.afklive.in/` | one 301 to `https://afklive.in/pricing` (or `/`) |
| `/pricing/` | 301, `Location: /pricing` |
| `/nope` | 404 (the "This page is off air" page), no `Location` |
| `/site/index.html` | 301, `Location: /` |
| `/studio` (signed out) | 302 to `/login` |
| `/site/assets/*.css`, `*.woff2` | 200, `Cache-Control: max-age=31536000, public, immutable` |

The sitemap must list 18 addresses, none of them `/login`, `/register` or `/status`.

### Google Search Console checklist

1. Add a **Domain property** for `afklive.in`, verified with the DNS TXT record Search Console gives
   (covers `www`, `http` and `https`).
2. **Sitemaps**: submit `https://afklive.in/sitemap.xml`. Expect "Success" and 18 discovered pages.
3. **URL Inspection** → `https://afklive.in/` → **Test live URL**: "URL is available to Google",
   status 200, the user-declared canonical is the Google-selected one, and the screenshot is the
   homepage, not the sign-in page. Then **Request indexing**. Repeat for `/hi`, `/pricing`, `/help` and
   the four `/for/*` pages.
4. **Settings → robots.txt**: fetched recently with 200 (it was a 302 to sign-in before 23 Oct 2026).
5. **Settings → Crawl stats**: 3xx responses to `/login` should fall away after this release; 404s for
   old or mistyped addresses are expected and harmless.
6. **Pages report**: watch "Discovered – currently not indexed" and "Duplicate, Google chose different
   canonical" (any `/site/*.html` there should move to "Page with redirect").
7. **Enhancements**: Breadcrumbs and FAQ should show valid items. SoftwareApplication can't become a
   rich result until the app has real ratings or reviews (Google requires one); never add made-up ones.
8. **Manual actions** and **Security issues**: both empty.
9. Add the site to **Bing Webmaster Tools** (it can import the Search Console property); Bing feeds
   DuckDuckGo and several AI search tools.
