#!/bin/bash
# Restore a backup made by db-backup.sh into an EMPTY database.
#
#   db-restore.sh --list                         list the backups in the bucket
#   db-restore.sh latest <database>              restore the newest daily backup into <database>
#   db-restore.sh <file>.dump <database>         restore that backup (daily/ or weekly/)
#   db-restore.sh ... <database> --into-live     allow <database> to be POSTGRES_DB (disaster recovery)
#
# The target database must already exist, be owned by POSTGRES_USER and have no tables; the script
# never drops or overwrites anything. Monthly drill and disaster recovery: docs/operations.md#database-backups.
# Same environment as db-backup.sh (afklive.env + backup.env). Secrets are never printed.
set -Eeuo pipefail
umask 077

log() { echo "db-restore: $*"; }
fail() { echo "db-restore: FAILED: $*" >&2; exit 1; }
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

usage() { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

[ $# -ge 1 ] || usage
for v in POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD; do
  [ -n "${!v:-}" ] || fail "$v is not set"
done
ENDPOINT=$(first_set BACKUP_S3_ENDPOINT STORAGE_ENDPOINT DO_SPACES_ENDPOINT)
BUCKET=$(first_set BACKUP_S3_BUCKET STORAGE_BUCKET DO_SPACES_BUCKET)
ACCESS_KEY=$(first_set BACKUP_S3_ACCESS_KEY STORAGE_ACCESS_KEY DO_SPACES_KEY)
SECRET_KEY=$(first_set BACKUP_S3_SECRET_KEY STORAGE_SECRET_KEY DO_SPACES_SECRET)
[ -n "$ENDPOINT" ] && [ -n "$BUCKET" ] && [ -n "$ACCESS_KEY" ] && [ -n "$SECRET_KEY" ] \
  || fail "BACKUP_S3_ENDPOINT, BACKUP_S3_BUCKET, BACKUP_S3_ACCESS_KEY and BACKUP_S3_SECRET_KEY are needed"
for tool in pg_restore psql rclone; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool is not installed"
done

export RCLONE_CONFIG_AFKBACKUP_TYPE=s3
if [[ $ENDPOINT == *.r2.cloudflarestorage.com* ]]; then
  export RCLONE_CONFIG_AFKBACKUP_PROVIDER=Cloudflare
else
  export RCLONE_CONFIG_AFKBACKUP_PROVIDER=Other
fi
export RCLONE_CONFIG_AFKBACKUP_ENDPOINT="$ENDPOINT"
export RCLONE_CONFIG_AFKBACKUP_REGION="${BACKUP_S3_REGION:-auto}"
export RCLONE_CONFIG_AFKBACKUP_ACCESS_KEY_ID="$ACCESS_KEY"
export RCLONE_CONFIG_AFKBACKUP_SECRET_ACCESS_KEY="$SECRET_KEY"
export RCLONE_CONFIG_AFKBACKUP_NO_CHECK_BUCKET=true
unset ACCESS_KEY SECRET_KEY
REMOTE="afkbackup:${BUCKET}/${BACKUP_PREFIX:-db}"

if [ "$1" = "--list" ]; then
  for folder in daily weekly; do
    echo "$folder/"
    rclone lsl "$REMOTE/$folder/" || fail "could not list $folder backups"
  done
  exit 0
fi

[ $# -ge 2 ] || usage
WANT=$1
TARGET=$2
INTO_LIVE=no
[ "${3:-}" = "--into-live" ] && INTO_LIVE=yes
[[ $TARGET =~ ^[A-Za-z0-9_-]+$ ]] || fail "unexpected database name: $TARGET"
if [ "$TARGET" = "$POSTGRES_DB" ] && [ "$INTO_LIVE" != yes ]; then
  fail "$TARGET is the live database; restore into a scratch database, or add --into-live (disaster recovery only)"
fi

# --- which backup -----------------------------------------------------------------------------------
if [ "$WANT" = latest ]; then
  NAME=$(rclone lsf --files-only "$REMOTE/daily/" | grep -E "^${POSTGRES_DB}-[0-9]{4}-[0-9]{2}-[0-9]{2}\.dump$" | sort | tail -n 1 || true)
  [ -n "$NAME" ] || fail "no daily backups found"
  SOURCE="$REMOTE/daily/$NAME"
else
  [[ $WANT =~ ^[A-Za-z0-9_.-]+\.dump$ ]] || fail "give a backup file name like ${POSTGRES_DB}-2026-01-31.dump, or latest"
  NAME=$WANT
  if rclone lsf --files-only "$REMOTE/daily/" | grep -qxF "$NAME"; then
    SOURCE="$REMOTE/daily/$NAME"
  elif rclone lsf --files-only "$REMOTE/weekly/" | grep -qxF "$NAME"; then
    SOURCE="$REMOTE/weekly/$NAME"
  else
    fail "$NAME is not in daily/ or weekly/ (try --list)"
  fi
fi

export PGPASSWORD="$POSTGRES_PASSWORD"
PSQL=(psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" --no-password -v ON_ERROR_STOP=1 -tA)

# --- the target must exist and be empty -------------------------------------------------------------
"${PSQL[@]}" -d "$TARGET" -c "select 1" >/dev/null 2>&1 \
  || fail "can't connect to database $TARGET; create it first: sudo -u postgres createdb -O $POSTGRES_USER $TARGET"
TABLES=$("${PSQL[@]}" -d "$TARGET" -c "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r','p')")
[ "$TABLES" = 0 ] || fail "database $TARGET is not empty ($TABLES tables); restore only into an empty database"

# --- download, check, restore -----------------------------------------------------------------------
WORK=$(mktemp -d "${BACKUP_TMPDIR:-/var/tmp}/afklive-restore.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
log "downloading $NAME"
rclone copyto "$SOURCE" "$WORK/$NAME" --retries 3 || fail "download failed"
pg_restore --list "$WORK/$NAME" >/dev/null || fail "pg_restore can't read $NAME"

log "restoring $NAME into $TARGET"
pg_restore -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" --no-password -d "$TARGET" \
  --no-owner --no-privileges --exit-on-error --jobs=2 "$WORK/$NAME" || fail "pg_restore failed"

# --- a quick look at what came back -----------------------------------------------------------------
RESTORED=$("${PSQL[@]}" -d "$TARGET" -c "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r','p')")
CHANGESETS=$("${PSQL[@]}" -d "$TARGET" -c "select count(*) from databasechangelog" 2>/dev/null || echo "?")
USERS=$("${PSQL[@]}" -d "$TARGET" -c "select count(*) from users" 2>/dev/null || echo "?")
log "restored: $RESTORED tables, $CHANGESETS changesets, $USERS users"
log "done"
