#!/bin/bash
set -e
set -o pipefail

# --- Configuration ---
DATE=$(date +%Y-%m-%d_%H%M%S)
BACKUP_FILENAME="afklive_db_backup_${DATE}.sql.gz"
# We store the backup locally first
BACKUP_FILE="./${BACKUP_FILENAME}"

# --- Check Environment Variables ---
REQUIRED_VARS=(
  "POSTGRES_HOST"
  "POSTGRES_PORT"
  "POSTGRES_DB"
  "POSTGRES_USER"
  "POSTGRES_PASSWORD"
  "DO_SPACES_ENDPOINT"
  "DO_SPACES_BUCKET"
  "DO_SPACES_KEY"
  "DO_SPACES_SECRET"
)

echo "Checking environment variables..."
MISSING_VARS=0
for VAR in "${REQUIRED_VARS[@]}"; do
  if [ -z "${!VAR}" ]; then
    echo "Error: Environment variable $VAR is not set."
    MISSING_VARS=1
  fi
done

if [ $MISSING_VARS -ne 0 ]; then
  echo "Please set all required environment variables."
  exit 1
fi

# --- Check Required Tools ---
echo "Checking dependencies..."
if ! command -v pg_dump &> /dev/null; then
    echo "Error: pg_dump (postgresql-client) is not installed."
    exit 1
fi

if ! command -v aws &> /dev/null; then
    echo "Error: aws-cli is not installed."
    exit 1
fi

if ! command -v gzip &> /dev/null; then
    echo "Error: gzip is not installed."
    exit 1
fi

# --- Perform Backup ---
echo "Starting database backup for DB: ${POSTGRES_DB} on host: ${POSTGRES_HOST}..."

# Export PGPASSWORD to avoid password prompt
export PGPASSWORD="${POSTGRES_PASSWORD}"

# pg_dump options:
# -h host, -p port, -U user, -d dbname
# We pipe to gzip for compression
if pg_dump -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_USER}" "${POSTGRES_DB}" | gzip > "${BACKUP_FILE}"; then
  echo "Backup created successfully: ${BACKUP_FILE}"
  # Verify file size is greater than 0
  if [ ! -s "${BACKUP_FILE}" ]; then
    echo "Error: Backup file is empty."
    rm -f "${BACKUP_FILE}"
    exit 1
  fi
else
  echo "Error: Database backup failed."
  rm -f "${BACKUP_FILE}"
  exit 1
fi

# --- Upload to S3/Spaces ---
S3_DEST="s3://${DO_SPACES_BUCKET}/db-backups/${BACKUP_FILENAME}"
echo "Uploading to S3 compatible storage: ${S3_DEST}..."

# Set AWS CLI environment variables from DO_SPACES_*
export AWS_ACCESS_KEY_ID="${DO_SPACES_KEY}"
export AWS_SECRET_ACCESS_KEY="${DO_SPACES_SECRET}"
export AWS_DEFAULT_REGION="${DO_SPACES_REGION:-us-east-1}"

# Upload
if aws s3 cp "${BACKUP_FILE}" "${S3_DEST}" --endpoint-url "${DO_SPACES_ENDPOINT}"; then
  echo "Upload successful."

  # Cleanup local file
  rm -f "${BACKUP_FILE}"
  echo "Local backup file removed."
else
  echo "Error: Upload to S3 failed."
  # We do NOT remove the local file if upload fails, to allow manual recovery
  echo "Keeping local backup file: ${BACKUP_FILE}"
  exit 1
fi

echo "Backup process completed successfully."
