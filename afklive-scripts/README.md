# AFKLive Scripts

This directory contains utility and maintenance scripts for the AFKLive project.

## Database backups (`db-backup.sh`, `db-restore.sh`)

Nightly `pg_dump` (custom format) to an S3-compatible bucket (Cloudflare R2) with rclone, keeping 7 daily
and 4 weekly backups, run by `systemd/afklive-db-backup.timer`. `db-restore.sh` restores one into an
empty database (the monthly drill) and lists what's there. Setup, environment variables, the drill and
disaster recovery: [docs/operations.md, "Database backups"](../docs/operations.md#database-backups).

`backup_db.sh` is deprecated and only runs `db-backup.sh`.
