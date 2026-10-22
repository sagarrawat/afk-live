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
