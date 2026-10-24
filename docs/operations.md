# Operations

## Processes

AFK Live runs as two processes from the same jar:

| Service | Role | What it does | Deployed |
| --- | --- | --- | --- |
| `afklive` | `STREAMING_ROLE=web` | Website and API, billing, scheduled starts/stops | On every push to `main` (GitHub Actions) |
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
   query strings for `/internal/ingest/auth` on any proxy in front of the web app.
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
