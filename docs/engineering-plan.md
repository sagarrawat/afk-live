# Engineering plan: the Trust phase

**Each workstream below says how it will be built, what it touches, how it can fail, how it is
tested and how it ships.** The stories and their order are in `docs/roadmap.md`; this is the how.
Every workstream follows the repo rules in `CLAUDE.md` and the FinOps rules in the roadmap.

## Shared rules for this phase

- **Database is the only channel between web and worker**, with conditional (fenced) updates. New
  coordination (handovers, copies, deploy slots) follows the same pattern as claim/heartbeat/takeover.
- **Expand, then contract.** Every changeset must work with the previous release of both web and
  worker, because during a zero-downtime deploy both run at once. Add first; use in the next release;
  drop a release later.
- **Changeset numbers per branch** to avoid clashes while work runs in parallel: 022 playout copies,
  025–029 truth of live, 030–034 worker handover, 035–039 support and email, 040–044 onboarding,
  045–049 cost metering.
- **Two deploy batches.** Web-only changes ship first. Worker changes ship together, after the
  handover is installed, so that batch is the first uninterrupted worker deploy.
- **Feature switches** for anything that changes a live stream's behaviour (`app.*` properties with
  safe defaults), so a bad release is turned off with an environment change, not a revert.

## 1. Truth of live: RE-8, RE-9, GL-1 (branch `feature/truth-of-live`)

**Approach.** Each relay (one FFmpeg per destination, `StreamRunner.Relay`) already knows whether its
process is running; it doesn't know whether the platform accepts data. Measure what the relay actually
consumes from its input queue: a relay that has drained data steadily for 10 seconds is "On"; one
whose process failed to open the output, exits, or stops draining is "Not reaching", with the last
FFmpeg error translated by the same explainer the destination test uses.

| Part | Where |
| --- | --- |
| Per-destination state and reason | `StreamRunner.Relay` → listener → `stream_job_destinations` |
| Bytes sent per destination (for FO-3) | Same counter, written with the heartbeat |
| Stream badge (Live / Live with problems / Not reaching anyone) | API view of a run; Home, stream header, `remote.html` |
| All destinations down 60 s → incident and alert | `AlertMonitor` (web, claim-based across instances) |
| Destination events on the timeline; one destination down 5 min alerts | New events table, uptime view |
| Pre-flight test before Go live | `DestinationTestService` reused; skip when tested in the last 24 h and unchanged |

**Failure modes.** A slow but connected platform must not flap between states: use a 10 s window and
require two bad windows before "Not reaching". A pre-flight test must never publish to a key a live
run of the same stream is using. **Tests**: real FFmpeg relays to files (works), to an output that
can't open (fails with the reason), a test clock for the 60 s incident, Vitest for the badge.
**Ships** in the worker batch (worker + web).

## 2. Zero-downtime web deploys: OPS-4 (branch `ops/web-zero-downtime`)

**Approach.** Two systemd slots of the web app on two local ports; Caddy proxies to both with active
health checks and sends traffic only to healthy ones. The deploy script starts the idle slot, waits
for Spring Boot's readiness probe (served on localhost only), lets Caddy pick it up, then stops the
old slot with graceful shutdown so in-flight requests finish.

**Things that break when two web versions overlap, and the answer.**

| Risk | Answer |
| --- | --- |
| Migration incompatible with the old version | Expand-then-contract rule, written into `CLAUDE.md` |
| Scheduled jobs running twice | All sweepers and schedulers are claim-based; verify each, guard any that isn't |
| Sessions in memory: switching logs everyone out | Store sessions in PostgreSQL (Spring Session JDBC) if cheap; otherwise document |
| Startup takes about 70 s | Fine: the old slot serves until the new one is ready |
| Memory: two web JVMs plus the worker on 8 GB | Check heap sizes in the units; lower if headroom is thin |

**Tests**: readiness only on localhost, graceful shutdown configured, script `--dry-run` and
shellcheck. The server setup (units, Caddyfile) is applied by hand once, from `docs/operations.md`.
**Ships** with the web batch; the GitHub workflow keeps the old restart path behind an input.

## 3. Zero-interruption worker deploys: OPS-3 (branch `feature/worker-handover`)

**Approach.** The deploy starts the new worker in a second slot (its own worker id) and marks the
old one as draining in the database. A draining worker claims nothing new and offers its streams for
handover, one at a time:

```
old worker            database                         new worker
  │  DRAINING ───────────►│
  │  offer stream 42 ────►│ handover: from=old            │
  │                       │◄──── claim handover (fenced) ─┤ start stream 42 at old's position
  │                       │                               │ YouTube → other ingest (backup/primary)
  │                       │◄──── all destinations LIVE ───┤
  │◄─ handover done ──────│                               │
  │  stop copy, release   │ owner = new (fenced)          │
```

**YouTube continuity** comes from its redundant ingest: the old run publishes to primary, the new
one to backup (or the reverse), and YouTube switches without ending the broadcast. Which ingest a run
uses is recorded per run, so consecutive deploys alternate. Other platforms: stop old, start new,
about a second of reconnect.

**Failure modes.** New run not live in 60 s → abandon, old keeps streaming. Old worker dies mid-way
→ normal takeover after 45 s silence still applies. New worker dies mid-way → handover times out, old
keeps it. A stream must never end with two owners or none; each case is a test. **CPU**: two encodes
for one stream for about 30 s, so handovers are paced. **Tests**: the ingest choice as a pure
function; full handover with real FFmpeg and file destinations; each crash case. YouTube's failover
itself can only be confirmed on production, once, with a test stream before creators rely on it.
**Ships** first in the worker batch; its own deploy is the last interrupting one.

## 4. Support and email: OPS-1, OPS-2, AD-6 (branch `feature/support-ops`)

**Approach.**
- **Email off is a visible state, not a silent one:** a startup warning, an admin banner, automatic
  verification of password sign-ups while it's off, an honest forgot-password message, a send log
  per user, and an admin "send test email".
- **Reset links use the configured public base URL,** never the request's host, which is wrong
  behind Caddy.
- **Expired forms re-render with "This page expired."** The app's fetch wrapper retries once on an
  expired CSRF token. The error page says "our side" only for real server errors (5xx) and shows a
  request id that is also in the logs.
- **Support view:** the stream as configured (source size, frame rate, shape, output profile), each
  destination's host, masked key and a key-format check, the last error, and the last 200 lines of
  FFmpeg output with keys redacted (a tested redaction function), plus an audited "send a test".

**Risk**: logging FFmpeg output can leak keys; every stored line passes redaction, and tests feed
real FFmpeg error lines containing keys. **Ships** in the web batch; the log capture part needs the worker.

## 5. Onboarding: GL-6, ST-6, MK-4, then ON-8 (branch `feature/onboarding-youtube`)

**Approach.** A dismissible "What you'll see on YouTube" card (preference stored server-side), a
shape suggestion driven by the probed size of ready videos, and help articles in the public site,
each linked from the in-app message it explains. **ON-8** (setup ends with proof) follows once RE-8 is
in: the setup's last step waits for "On YouTube" and records "first live confirmed" per user, which
becomes the activation metric. **Ships** in the web batch (ON-8 with the worker batch).

## 6. CPU: FO-1 (PR #163) and FO-6

**Done in #163:** playout copies at the stream's canvas and frame rate, made once in the background at
the lowest priority, picked up at the next item; frame-rate reduction before scaling; a busy worker
leaves new streams to others for 45 s. **Measured**: about 1.35 → 0.6 vCPU for a 1080p60 source on a
720p30 stream.

**Encoder preset (perf/encoder-preset):** the live x264 encoder moved from `veryfast` to `superfast`
(configurable, `APP_STREAMING_X264PRESET`). Production had two 720p30 studio encoders at 0.8 and 1.24
cores on a 4-vCPU worker at 70–90%; on 720x1280 footage superfast costs 30–45% less encoder CPU for an
SSIM drop of 0.001–0.002. Both presets write identical SPS/PPS, so existing profile copies (FO-2) stay
valid; copies now record their preset so a later change to another preset family re-makes them.
A per-encoder thread cap measured no saving and stays off by default.

**FO-6 (next, small):** record when a copy was last played (written at most every 10 minutes from the
worker's lookup), delete copies unplayed for 30 days in the web sweeper, and show copy storage in
admin. Copies are made again on demand, so deletion is always safe.

## 7. Copy-mode playout: FO-2 (built: PR feature/copy-mode, off by default)

**Approach.** For a playlist stream with no live overlay, scene or phone input, the studio's decode
and encode are pure waste: prepare each file once at the exact stream profile (codec, resolution,
frame rate, keyframe every 2 s, CBR bitrate, AAC 44.1 kHz, loudness applied) and play the list with
FFmpeg's concat demuxer and stream copy into the same relays. That is about 0.05 vCPU instead of 0.6.

| Question | Plan |
| --- | --- |
| Which streams qualify | Decided per run from the spec: playlist source, no overlays, no scenes, no music bed, all items have a profile copy |
| Profile copies | A second kind of playout copy (exact profile, not the high-quality intermediate), same table with a `profile` column |
| Live edits (skip, reorder) | Rewrite the concat list ahead of the current item; skip restarts the copy process at the next item |
| Adding an overlay while live | Switch the run to the studio at the next item boundary; relays stay connected because they survive an encoder restart today (RE-1); verify timestamp continuity with a test |
| Gaps between files | Profile copies share parameters exactly, so concat is seamless; tested with A/V drift measured over 100 loops |

**Risk**: timestamp jumps when switching between copy mode and the studio; a soak test (OPS-6) must
pass before turning it on. **Ships** behind `app.streaming.copy-mode` (default off), first for
admin-chosen streams.

## 8. Cost metering and budgets: FO-3, FO-5 (branch `feature/cost-metering`)

**Approach.** Bytes sent per destination (from workstream 1) plus CPU per worker (already reported
every 30 s) give cost per stream-hour:

```
server cost per stream-hour = monthly server bill / stream-hours delivered that month
traffic cost per stream-hour = max(0, GB sent − included GB) × $0.01 / stream-hours
```

The bill and included transfer are environment settings (`APP_MONTHLY_SERVER_COST` exists; add
`APP_INCLUDED_TRANSFER_GB` and `APP_TRANSFER_PRICE_PER_GB`). Admin shows the cost per plan and
resolution and a month-end projection. FO-5 alerts admins at 70% and 90% of included transfer. No new
service: the numbers come from rows the app already writes.

## 9. Bitrates: FO-4 (next, small, measured first)

720p30 is sent at 4 Mbps today; YouTube's range for 720p30 goes down to 1.5 Mbps. Before changing
anything, encode representative footage (gameplay, music still, talking head) at 2.5, 3, 3.5 and
4 Mbps and compare quality with FFmpeg's SSIM. The lowest rate within 0.5% SSIM of 4 Mbps becomes the
default. A 25% lower rate is 25% less traffic for every stream, forever. 1080p stays at YouTube's
5 Mbps minimum or above (repo rule).

The tool is `afklive-scripts/bitrate-study.sh` (run it on staging with real footage, never on a
streaming server). A first run on synthetic worst-case footage (noisy 1080p60 motion at 720x1280) gave
SSIM 0.952 at 2.5 Mbps, 0.955 at 3.5 and 0.955 at 4: already within 0.005, which suggests 3 Mbps or
less is enough for most content. Confirm on real gameplay and music footage before changing the default.

## 10. Staging and soak: OPS-5, OPS-6 (next)

- **Staging (OPS-5)** runs on the production droplet as a third web slot and a worker slot with a
  stream limit of 1, its own database on the same PostgreSQL server, and a `staging.` subdomain in
  Caddy. Every merge deploys to staging first; production follows a manual approval. No new server.
- **Soak (OPS-6)** runs on staging, not on GitHub's paid minutes: a nightly 6-hour stream to a local
  file sink, checking memory, timestamps, A/V drift and reconnects, with results posted to a status
  row the PR check reads. A 72-hour run before major releases.

## 11. Fleet: FO-7, FO-8 (later)

- **Scaling (FO-7):** a small scheduler on the web side adds a droplet from a saved image when fleet
  CPU is over 75%, and drains one (using OPS-3) under 40%. It never goes below one, and logs every
  change with its cost.
- **Placement (FO-8):** a second pool where traffic is included runs 24/7 playlist streams. Phone
  streams stay near India. Placement is a worker label matched at claim time.

Both depend on OPS-3 (draining without interruption) and FO-3 (knowing the cost).

## Order of delivery

1. **In review now:** #162 (key checks), #163 (CPU), #164 (roadmap), #160 (admin).
2. **In progress:** workstreams 1–5.
3. **Next, in parallel when 1–5 land:** FO-6 (with #163), FO-3/FO-5, FO-4 measurement, OPS-5.
4. **Then:** FO-2 behind a switch, soak-tested; ON-8.
5. **Later:** FO-7, FO-8, MS-7, BI-4.
