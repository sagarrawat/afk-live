#!/bin/bash
# Nightly PostgreSQL backup to S3-compatible storage (Cloudflare R2 by default), with retention.
#
#   db-backup.sh            dump, check, upload, keep 7 daily + 4 weekly, then prune older ones
#
# Run by afklive-scripts/systemd/afklive-db-backup.timer (setup: docs/operations.md#database-backups).
# Every failure exits non-zero with a "db-backup: FAILED" line in the journal; nothing is pruned unless
# tonight's dump was uploaded. Secrets are read from the environment and never printed.
#
# Environment (afklive.env + backup.env):
#   POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD   the app's own settings
#   BACKUP_S3_ENDPOINT    e.g. https://<accountid>.r2.cloudflarestorage.com (fallback STORAGE_ENDPOINT, DO_SPACES_ENDPOINT)
#   BACKUP_S3_BUCKET      e.g. afklive-backups                         (fallback STORAGE_BUCKET, DO_SPACES_BUCKET)
#   BACKUP_S3_ACCESS_KEY  BACKUP_S3_SECRET_KEY                          (fallback STORAGE_*/DO_SPACES_* keys)
#   BACKUP_S3_REGION      default "auto" (R2)
#   BACKUP_PREFIX         folder in the bucket, default "db"
#   BACKUP_KEEP_DAILY     default 7     BACKUP_KEEP_WEEKLY  default 4
#   BACKUP_WEEKLY_DAY     ISO weekday that is also kept as a weekly copy, default 7 (Sunday)
#   BACKUP_PING_URL       optional dead man's switch (e.g. healthchecks.io): pinged on success, /fail on failure
#   BACKUP_TMPDIR         where the dump is written before upload, default /var/tmp
#   BACKUP_DATE           YYYY-MM-DD, default today (UTC); for tests and re-runs
set -Eeuo pipefail
umask 077

log() { echo "db-backup: $*"; }

ping_url() {
  # $1 = "" for success or "/fail"
  if [ -n "${BACKUP_PING_URL:-}" ] && command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 10 --retry 2 -o /dev/null "${BACKUP_PING_URL}$1" || log "warning: could not reach the ping URL"
  fi
}

fail() {
  echo "db-backup: FAILED: $*" >&2
  ping_url /fail
  exit 1
}

trap 'fail "unexpected error on line $LINENO"' ERR

first_set() {
  local v
  for v in "$@"; do
    if [ -n "${!v:-}" ]; then
      printf '%s' "${!v}"
      return 0
    fi
  done
  return 0
}

# --- settings ---------------------------------------------------------------------------------------
for v in POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD; do
  [ -n "${!v:-}" ] || fail "$v is not set"
done
ENDPOINT=$(first_set BACKUP_S3_ENDPOINT STORAGE_ENDPOINT DO_SPACES_ENDPOINT)
BUCKET=$(first_set BACKUP_S3_BUCKET STORAGE_BUCKET DO_SPACES_BUCKET)
ACCESS_KEY=$(first_set BACKUP_S3_ACCESS_KEY STORAGE_ACCESS_KEY DO_SPACES_KEY)
SECRET_KEY=$(first_set BACKUP_S3_SECRET_KEY STORAGE_SECRET_KEY DO_SPACES_SECRET)
REGION=${BACKUP_S3_REGION:-auto}
[ -n "$ENDPOINT" ] || fail "BACKUP_S3_ENDPOINT is not set"
[ -n "$BUCKET" ] || fail "BACKUP_S3_BUCKET is not set"
[ -n "$ACCESS_KEY" ] || fail "BACKUP_S3_ACCESS_KEY is not set"
[ -n "$SECRET_KEY" ] || fail "BACKUP_S3_SECRET_KEY is not set"
PREFIX=${BACKUP_PREFIX:-db}
KEEP_DAILY=${BACKUP_KEEP_DAILY:-7}
KEEP_WEEKLY=${BACKUP_KEEP_WEEKLY:-4}
WEEKLY_DAY=${BACKUP_WEEKLY_DAY:-7}
DATE=${BACKUP_DATE:-$(date -u +%F)}
[[ $DATE =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || fail "BACKUP_DATE must look like 2026-01-31"
[[ $KEEP_DAILY =~ ^[1-9][0-9]*$ && $KEEP_WEEKLY =~ ^[0-9]+$ ]] || fail "BACKUP_KEEP_DAILY must be 1 or more, BACKUP_KEEP_WEEKLY 0 or more"
[[ $POSTGRES_DB =~ ^[A-Za-z0-9_-]+$ ]] || fail "POSTGRES_DB has characters this script doesn't expect"

for tool in pg_dump pg_restore rclone; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool is not installed (see docs/operations.md#database-backups)"
done

# rclone reads its remote from these variables: no config file with keys on disk, nothing on the command line.
export RCLONE_CONFIG_AFKBACKUP_TYPE=s3
if [[ $ENDPOINT == *.r2.cloudflarestorage.com* ]]; then
  export RCLONE_CONFIG_AFKBACKUP_PROVIDER=Cloudflare
else
  export RCLONE_CONFIG_AFKBACKUP_PROVIDER=Other
fi
export RCLONE_CONFIG_AFKBACKUP_ENDPOINT="$ENDPOINT"
export RCLONE_CONFIG_AFKBACKUP_REGION="$REGION"
export RCLONE_CONFIG_AFKBACKUP_ACCESS_KEY_ID="$ACCESS_KEY"
export RCLONE_CONFIG_AFKBACKUP_SECRET_ACCESS_KEY="$SECRET_KEY"
# A token limited to one bucket can't create buckets; don't try.
export RCLONE_CONFIG_AFKBACKUP_NO_CHECK_BUCKET=true
unset ACCESS_KEY SECRET_KEY
REMOTE="afkbackup:${BUCKET}/${PREFIX}"
NAME="${POSTGRES_DB}-${DATE}.dump"

# --- dump -------------------------------------------------------------------------------------------
WORK=$(mktemp -d "${BACKUP_TMPDIR:-/var/tmp}/afklive-backup.XXXXXX")
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
DUMP="$WORK/$NAME"

log "dumping $POSTGRES_DB"
export PGPASSWORD="$POSTGRES_PASSWORD"
pg_dump -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  --format=custom --compress=6 --no-password --file="$DUMP" || fail "pg_dump failed"
unset PGPASSWORD

[ -s "$DUMP" ] || fail "the dump is empty"
# A dump pg_restore can't read is not a backup.
pg_restore --list "$DUMP" >/dev/null || fail "pg_restore can't read the dump"
SIZE=$(wc -c <"$DUMP" | tr -d ' ')
log "dump ok: $NAME ($SIZE bytes)"

# --- upload -----------------------------------------------------------------------------------------
rclone copyto "$DUMP" "$REMOTE/daily/$NAME" --retries 3 --low-level-retries 10 || fail "upload failed"
log "uploaded to $BUCKET/$PREFIX/daily/$NAME"

WEEKDAY=$(date -u -d "$DATE" +%u)
if [ "$WEEKDAY" = "$WEEKLY_DAY" ] && [ "$KEEP_WEEKLY" -gt 0 ]; then
  # Server-side copy: no second upload.
  rclone copyto "$REMOTE/daily/$NAME" "$REMOTE/weekly/$NAME" --retries 3 || fail "weekly copy failed"
  log "kept as weekly: $BUCKET/$PREFIX/weekly/$NAME"
fi

# --- retention (only after tonight's dump is safely uploaded) ---------------------------------------
prune() {
  # $1 = folder, $2 = how many to keep. Only touches files this script names; newest by name (the date).
  local folder=$1 keep=$2 listing old
  listing=$(rclone lsf --files-only "$REMOTE/$folder/") || fail "could not list $folder backups"
  mapfile -t old < <(printf '%s\n' "$listing" \
    | grep -E "^${POSTGRES_DB}-[0-9]{4}-[0-9]{2}-[0-9]{2}\.dump$" | sort -r | tail -n +"$((keep + 1))" || true)
  local f
  for f in "${old[@]}"; do
    [ -n "$f" ] || continue
    [ "$f" != "$NAME" ] || continue
    rclone deletefile "$REMOTE/$folder/$f" || fail "could not delete old backup $folder/$f"
    log "pruned $folder/$f"
  done
}
prune daily "$KEEP_DAILY"
if [ "$KEEP_WEEKLY" -gt 0 ]; then
  prune weekly "$KEEP_WEEKLY"
fi

ping_url ""
log "done"
