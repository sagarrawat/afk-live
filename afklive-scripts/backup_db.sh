#!/bin/bash
# Deprecated: use db-backup.sh (pg_dump custom format, rclone, retention) and the systemd timer in
# afklive-scripts/systemd/afklive-db-backup.timer; see docs/operations.md#database-backups.
# Kept so an old crontab entry still makes a backup; the DO_SPACES_* names are fallbacks there.
set -euo pipefail
echo "backup_db.sh is deprecated; running db-backup.sh" >&2
exec "$(dirname "$0")/db-backup.sh" "$@"
