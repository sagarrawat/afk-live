# AFK Live

Cloud 24/7 live streaming for small creators: upload once, stay live on YouTube and other platforms
without a PC, plus a cloud studio for creators who stream from their phone.

**Read `docs/product-spec.md` before any feature work.** It holds the vision, personas, design
direction, 127 user stories with IDs (ON-1, PL-3, MS-2, ...), the release plan and the
non-functional requirements (continuity, quality, latency, cost). Reference story IDs in PRs.

## Repository layout

- `afklive-web/`: Spring Boot 4 (Java 21) app. One jar, two roles: web (API, site, billing) and stream worker.
  - `src/main/java/com/afklive/streamer/`: `endpoint/` (controllers), `service/`, `model/`, `repository/`, `config/`, `security/`
  - `streaming/`: the worker: `StreamWorker` (claims jobs, heartbeats, takeover), `StreamRunner` (one encoder + a relay per destination, restarts with backoff), `StreamPreparer`, `StreamSpec`
  - `streaming/studio/`: the cloud studio: a frame clock (`Studio`) feeding one long-lived encoder raw video + PCM; `PlaylistFeed` (gapless playlists), `LiveFeed` (never-drop phone input with BRB fallback), `StillFeed`, `DecoderSource` (FFmpeg decoders), `OverlayCompositor` (overlays drawn in Java). Everything a creator changes while live happens here, between frames, never by restarting FFmpeg
  - Saved streams (`LiveStream`, `/api/streams`, `LiveStreamService`, `StreamScheduleService`): a stream is a draft until it goes live; each go-live is a `StreamJob` whose spec carries a `StudioSpec`. Live edits become `stream_commands` that the worker applies between frames (`StudioRuns`). Library files reach workers through `MediaCache`
  - Phone streams: `IngestService` (per-stream ingest key, app links/QR, MediaMTX auth hook at `/internal/ingest/auth`); config in `afklive-scripts/mediamtx.yml`. Secrets at rest go through `util/SecretBox` (`EncryptedStringConverter`); `static/remote.html` is the phone-friendly remote control
  - Alerts: `NotificationService` (in-app list + email, dedupe windows), `AlertMonitor` (web side, every 30 s: outages that last ≥ 60 s open a `stream_incidents` row once across instances, recovery closes it)
  - `src/main/resources/db/changelog/`: Liquibase migrations (the only way to change the schema)
  - `src/main/resources/static/`: current frontend (Alpine + Tailwind CDN); to be replaced per the design direction
- `afklive-optimizer/`: AWS Lambda that normalizes uploaded videos with FFmpeg.
- `docs/product-spec.md`: product spec (source of truth for scope and priorities).
- `docs/operations.md`: how web and worker processes run, deploy, and the worker egress firewall.

## Build, test, run

```sh
mvn -B verify                                  # build + all tests (what CI runs on every PR)
mvn -B test -pl afklive-web -Dtest=SomeTest    # one test class
```

Tests that need the database extend `PostgresIntegrationTest`, which starts an embedded PostgreSQL
(no Docker needed). `StreamerApplicationTests` boots the whole app; keep it green.

Run locally:

```sh
docker compose -f docker-compose.dev.yml up -d          # PostgreSQL
SPRING_PROFILES_ACTIVE=dev mvn -pl afklive-web spring-boot:run
```

The `dev` profile needs no Google, payment, AI or cloud secrets and seeds a local admin
(`dev@afklive.local` / `devpassword`) via a Liquibase changeset in the `dev` context only.
FFmpeg must be on PATH.

## Rules

- Never push to `main`: it deploys to production automatically. Work on a branch and open a PR.
- Schema changes go in a new Liquibase changeset; never edit an applied one.
- Seed or demo data only in a changeset with `context:dev`. Nothing may create accounts at production startup.
- Secrets come from environment variables only. Optional integrations get empty defaults (`${VAR:}`) so the app boots without them.
- Every change ships with tests. Bug fixes get a test that fails before the fix.
- Streaming changes must respect the continuity rules in the spec: planned changes never disconnect a destination.
- Web and worker communicate only through the database, with conditional updates (claim, heartbeat, markEnded). Never signal processes by PID or keep stream state only in memory on the web side.
- Process-level behaviour is tested with real FFmpeg (StreamRunnerTest, StreamWorkerIntegrationTest, StudioTest, PlaylistFeedTest); keep those passing.
- Encoding must meet YouTube's ingest minimums (1080p30 ≥ 5 Mbps, keyframe every 2 s, AAC 44.1 kHz).
