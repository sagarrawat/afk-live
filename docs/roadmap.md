# Roadmap: the next phase

**The next phase is about trust, not features: when AFK Live says "Live", it must be live on YouTube,
a deploy must never cost a creator their stream, and every stream-hour must cost less than we charge
for it.** Story IDs below are in `docs/product-spec.md`. This file sets order, size and dependencies,
and records the evidence behind each choice. Update it when a story ships or the order changes.

Written 23 October 2026, after the first real users. Sizes: S (a day or two), M (a week), L (two weeks or more).

## What production taught us (23 October 2026)

| What happened | Root cause | Stories |
| --- | --- | --- |
| The app showed "Live" while YouTube got nothing, twice | Status reflects our encoder, not the platform; a server address was pasted as the key; a relay bug looped reconnects | RE-8, GL-1, DS-8 |
| YouTube sat on "Preparing…" | YouTube-side Auto-start / a broadcast stuck after restarts and a shape change; nothing in our app explains it | GL-6, MK-4 |
| A vertical clip streamed landscape | Shape is a setting creators don't find | ST-6 |
| One stream used 60–70% of a 4-vCPU server | Decoding a 1080p60 source every second for a 720p30 stream | FO-1, FO-2 |
| A user couldn't reset their password; email sign-ups can't finish | Email isn't configured in production and the app pretends it is | OPS-1 |
| Every merge gave about 70 s of 502s; a form opened before a deploy failed after it | Web deploys restart the only instance; expired forms show a server-error page | OPS-4, OPS-2 |
| A worker deploy restarts every live stream | RE-2 holds for web deploys only; the worker JVM owns the FFmpeg processes | OPS-3 |
| Diagnosing one user's stream needed a root console | Admin can't see destination errors, the configured key's shape or FFmpeg output | AD-6 |

## Order of work

| # | Story | Size | Depends on | Status |
| --- | --- | --- | --- | --- |
| 1 | OPS-1 Email works, or we say so | S | — | PR in progress |
| 2 | OPS-2 Friendly expired-form and error pages | S | — | PR in progress |
| 3 | DS-8 Refuse a server address as a stream key; test on save | S | — | PR #162 |
| 4 | FO-1 Playout copies + fps before scale + busy-worker check | M | — | PR #163 |
| 5 | RE-8 "Live" means the platform is receiving | M | — | PR in progress |
| 6 | GL-1 (finish) Pre-flight tests the destination | S | DS-8 | PR in progress |
| 7 | GL-6 Explain YouTube's side of going live | S | — | PR in progress |
| 8 | OPS-4 Web deploys without downtime | M | — | PR in progress |
| 9 | OPS-3 Worker deploys without interrupting streams | L | — | PR in progress |
| 10 | AD-6 Admin support view of a stream | M | RE-8 | PR in progress |
| 11 | RE-9 Destination events on the timeline; per-destination alerts | S | RE-8 | PR in progress |
| 12 | ST-6 Shape and frame rate from the video | S | DS-8 | PR in progress |
| 13 | MK-4 Help articles for real tickets | S | GL-6 | PR in progress |
| 14 | ON-8 Setup ends with proof that it worked | M | RE-8, GL-1 | Next |
| 15 | FO-3 Cost per stream-hour including bandwidth | S | — | Next |
| 16 | FO-2 Copy mode for playlists without live overlays | L | FO-1 | Next |
| 17 | FO-4 Right-size bitrates per profile | S | FO-3 | Next |
| 18 | FO-5 Bandwidth budget and alerts | S | FO-3 | Next |
| 19 | FO-6 Clean up playout copies nobody plays | S | FO-1 | Next |
| 20 | OPS-5 Staging on the production droplet | M | OPS-4 | Next |
| 21 | OPS-6 Nightly soak test | M | — | Next |
| 22 | FO-7 Add and remove workers on load | L | OPS-3, FO-3 | Later |
| 23 | FO-8 A bandwidth-cheap fleet for 24/7 playout | L | FO-7, FO-2 | Later |
| 24 | MS-7 Cloud recording of phone streams | M | FO-1 | Later |
| 25 | BI-4 USD card checkout | M | Provider decision | Later |

Releases: items 1–13 are the "Trust" release; deploy them in two batches, web-only first (1, 2, 3, 7,
8, 12, 13), then the worker batch (4, 5, 6, 9, 11) once OPS-3 is on the server, so the worker batch is
itself the first uninterrupted worker deploy. AD-6 goes with whichever batch it needs.

## Zero-downtime deploys without new servers

**Both processes deploy blue/green on the same droplet: start the new version next to the old one,
move the work over, then stop the old one.** Cost stays flat because the overlap lasts seconds to
minutes, not hours.

**Web (OPS-4).** Two systemd slots of the web app on two local ports; Caddy proxies to whichever is
healthy. A deploy starts the idle slot, waits for its readiness check, switches Caddy and stops the
old slot gracefully so in-flight requests finish. Rules that make the overlap safe:

- **Migrations are expand-then-contract.** A changeset must work with the previous app version: add
  columns and tables first, start using them in the next release, drop old ones a release later.
- **Background jobs are claim-based.** Sweepers, schedulers and alerts already claim work with
  conditional updates, so two instances never do it twice.
- **Sessions survive the switch** (stored in the database, or the switch logs people out; the PR
  decides and documents which).

**Worker (OPS-3).** A worker deploy starts the new worker in a second slot and asks the old one to
drain. Each stream is handed over one at a time:

1. The new worker starts the same stream from the old one's current playlist position.
2. YouTube destinations connect to YouTube's other ingest (backup if the old run uses primary).
   YouTube fails over between primary and backup ingest without ending the broadcast, so viewers see
   no gap.
3. When every destination of the new run is live, the old worker stops its copy (fenced in the
   database, so a stream never has two owners afterwards).
4. Platforms without a backup ingest (custom RTMP, Twitch, Facebook) get a reconnect of about a second.

During a handover two encodes run for that one stream, about 30 seconds, so handovers go one at a
time. If the new run isn't live within a minute, the handover is abandoned and the old run continues.
The ingest server (MediaMTX) changes rarely and keeps its own process; phones reconnect on their own.

**What this costs:** nothing per month. The extra memory is a second JVM for minutes; the extra CPU
is one stream's encode for 30 seconds per stream.

## FinOps: what a stream costs and how to keep it low

**After FO-1, CPU stops being the limit; bandwidth is.** Numbers for today's production (one
DigitalOcean droplet, 4 vCPU / 8 GB):

| Per 720p30 stream, one destination | Today | After FO-1 | After FO-2 (no live overlays) |
| --- | --- | --- | --- |
| CPU | ~1.35 vCPU (1080p60 source) | ~0.6 vCPU | ~0.05 vCPU |
| Streams per 4-vCPU server at 85% | 2 | 5 | bandwidth-bound |
| Outbound traffic at ~4.3 Mbit/s | ~1.4 TB a month | same | same |

CPU figures come from benchmarks on the same FFmpeg settings with a synthetic vertical 1080p60 clip
(PR #163); production gameplay compresses harder, so confirm on the server after deploy.

At 1.4 TB a month per destination, a droplet's included transfer covers only a few 24/7 streams before
DigitalOcean's $0.01 per GiB overage applies (about $14 a month per extra 24/7 destination). That is
why FO-3 (measure it), FO-4 (send no more bits than YouTube needs) and FO-8 (run 24/7 playout where
traffic is included) matter more than further CPU work once FO-1 ships.

**FinOps rules for every change**

1. Encode only when something on screen must change; otherwise copy.
2. Encode once per stream and fan out by copy; extra destinations cost bandwidth, never CPU.
3. Do expensive work once per file (playout copies, loudness), never once per second.
4. Background work runs at the lowest priority and never delays a live stream.
5. No always-on process or paid service without a cost line in the PR.
6. Every resource has a lifecycle: copies, recordings and logs are deleted when nothing uses them.
7. Price from measured cost per stream-hour (FO-3), not the other way round.

## Decisions needed

- **YouTube account connection (OAuth): yes or no.** With it, RE-8 can read YouTube's own broadcast
  status, turn Auto-start on for the creator and roll over 12-hour archives (RE-5). Without it, RE-8
  infers from the connection, which is as close as stream keys allow. The spec lists the connection
  as P0 (DS-1, YT-1) while the mobile flow is keys-only; pick one.
- **Activation is "first live confirmed" (RE-8), not "pressed Go live".**
- **Where 24/7 playout runs** (FO-8): stay on DigitalOcean and pay traffic, or add a provider whose
  servers include 20 TB.
