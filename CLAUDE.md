# AFK Live

Cloud 24/7 live streaming for small creators: upload once, stay live on YouTube and other platforms
without a PC, plus a cloud studio for creators who stream from their phone.

**Read `docs/product-spec.md` before any feature work.** It holds the vision, personas, design
direction, 127 user stories with IDs (ON-1, PL-3, MS-2, ...), the release plan and the
non-functional requirements (continuity, quality, latency, cost). Reference story IDs in PRs.

## Repository layout

- `afklive-web/`: Spring Boot 4 (Java 21) app: REST API, auth, scheduling, and (for now) the FFmpeg stream processes.
  - `src/main/java/com/afklive/streamer/`: `endpoint/` (controllers), `service/`, `model/`, `repository/`, `config/`, `security/`
  - `src/main/resources/db/changelog/`: Liquibase migrations (the only way to change the schema)
  - `src/main/resources/static/`: current frontend (Alpine + Tailwind CDN); to be replaced per the design direction
- `afklive-optimizer/`: AWS Lambda that normalizes uploaded videos with FFmpeg.
- `docs/product-spec.md`: product spec (source of truth for scope and priorities).

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
- Encoding must meet YouTube's ingest minimums (1080p30 ≥ 5 Mbps, keyframe every 2 s, AAC 44.1 kHz).
