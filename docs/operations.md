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
2. In `mediamtx.yml` set `authHTTPAddress` to the web app with the shared secret.
3. In `afklive.env` (web and workers):

| Variable | Example | Meaning |
| --- | --- | --- |
| `INGEST_PUBLIC_HOST` | `ingest.afklive.com` | What creators put in their phone app; empty turns phone streams off |
| `INGEST_RTMP_PORT` / `INGEST_SRT_PORT` | `1935` / `8890` | Public ports |
| `INGEST_READ_URL` | `rtmp://10.0.0.5:1935/live/{key}?user={user}&pass={pass}` | Where workers read a phone's feed |
| `INGEST_READ_USER` / `INGEST_READ_PASS` | `worker` / random | Workers' read credentials (checked by the web app) |
| `INGEST_AUTH_SECRET` | random | Shared with `mediamtx.yml` |
| `APP_ENCRYPTION_KEY` | `openssl rand -base64 32` | Encrypts stream keys and ingest keys at rest. **Set before the first deploy of this version**; keep it safe: without it stored keys can't be read |

If the worker egress firewall is loaded and the ingest server is on a private address, add an accept
rule for it (TCP 1935) in `worker-egress.nft`.
