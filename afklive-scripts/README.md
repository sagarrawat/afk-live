# AFKLive Scripts

This directory contains utility and maintenance scripts for the AFKLive project.

## Database Backup (`backup_db.sh`)

This script creates a compressed SQL dump of the PostgreSQL database and uploads it to an S3-compatible storage bucket (e.g., DigitalOcean Spaces).

### Prerequisites

Ensure the following tools are installed on the server/machine running the script:

1.  **PostgreSQL Client Tools** (`pg_dump`):
    ```bash
    sudo apt-get update && sudo apt-get install postgresql-client
    ```

2.  **AWS CLI** (`aws`):
    ```bash
    # Install AWS CLI v2
    curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
    unzip awscliv2.zip
    sudo ./aws/install
    ```

3.  **Gzip** (usually pre-installed):
    ```bash
    sudo apt-get install gzip
    ```

### Environment Variables

The script relies on environment variables for configuration. These should match the variables used by the `afklive-web` application.

| Variable | Description |
| :--- | :--- |
| `POSTGRES_HOST` | Database host (e.g., `localhost` or `db.example.com`) |
| `POSTGRES_PORT` | Database port (default: `5432`) |
| `POSTGRES_DB` | Name of the database to backup |
| `POSTGRES_USER` | Database username |
| `POSTGRES_PASSWORD` | Database password |
| `DO_SPACES_ENDPOINT` | S3-compatible endpoint URL (e.g., `https://nyc3.digitaloceanspaces.com`) |
| `DO_SPACES_BUCKET` | Name of the storage bucket |
| `DO_SPACES_KEY` | Access Key ID for the storage service |
| `DO_SPACES_SECRET` | Secret Access Key for the storage service |
| `DO_SPACES_REGION` | (Optional) Region for the bucket (default: `us-east-1` if not set) |

### Usage

Run the script manually:

```bash
./backup_db.sh
```

### Scheduling (Cron)

To schedule the backup to run daily at 3:00 AM, add the following line to your crontab (`crontab -e`):

```cron
0 3 * * * /path/to/afklive-scripts/backup_db.sh >> /var/log/afklive_backup.log 2>&1
```

**Note:** Ensure the cron job environment has access to the required environment variables. You may need to source a `.env` file or define the variables directly in the crontab.

Example sourcing `.env` in cron:

```cron
0 3 * * * . /path/to/afklive/.env && /path/to/afklive-scripts/backup_db.sh >> /var/log/afklive_backup.log 2>&1
```
