#!/bin/bash
# One-server setup for AFK Live (web app + stream worker + PostgreSQL + phone ingest + HTTPS) on a
# fresh Ubuntu 24.04 droplet. Use it as the droplet's user data ("initialization script"), or run it
# once as root. Safe to re-run: it keeps existing secrets and data.
#
# Afterwards: point DNS at the droplet (DOMAIN and INGEST_HOST below), add the deploy key's private
# half to GitHub secrets (DO_HOST, DO_USER=deploy, DO_SSH_KEY), then deploy the web app (push to
# main) and the worker (Actions > Deploy stream worker). Secrets are generated here, on the server,
# into /opt/afklive/afklive.env: back up APP_ENCRYPTION_KEY from that file to a password manager.
set -euo pipefail
exec > >(tee -a /var/log/afklive-setup.log) 2>&1
echo "=== AFK Live setup $(date -Is) ==="

DOMAIN="${DOMAIN:-afklive.in}"
INGEST_HOST="${INGEST_HOST:-ingest.afklive.in}"
DEPLOY_KEY="${DEPLOY_KEY:-ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN+e6xxq7s8PRoI6Dwak47IVvBbZpNi1y1d8dYSd+098 afklive-deploy}"
MEDIAMTX_VERSION="${MEDIAMTX_VERSION:-v1.9.3}"
MAX_STREAMS="${MAX_STREAMS:-4}"

export DEBIAN_FRONTEND=noninteractive

# --- swap (encoders spike memory) -----------------------------------------------------------------
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# --- packages ---------------------------------------------------------------------------------------
apt-get update -y
apt-get install -y openjdk-21-jre-headless ffmpeg postgresql nftables ufw curl debian-keyring \
  debian-archive-keyring apt-transport-https gnupg unattended-upgrades
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y && apt-get install -y caddy
fi

# --- users and folders -----------------------------------------------------------------------------
getent group afklive >/dev/null || groupadd afklive
id afklive >/dev/null 2>&1 || useradd --system --gid afklive --home /opt/afklive --shell /usr/sbin/nologin afklive
id afklive-worker >/dev/null 2>&1 || useradd --system --gid afklive --home /opt/afklive/worker --shell /usr/sbin/nologin afklive-worker
id afklive-ingest >/dev/null 2>&1 || useradd --system --home /opt/afklive/ingest --shell /usr/sbin/nologin afklive-ingest
if ! id deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash --groups afklive deploy
fi
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
grep -qF "$DEPLOY_KEY" /home/deploy/.ssh/authorized_keys 2>/dev/null || echo "$DEPLOY_KEY" >> /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys
# GitHub Actions may only restart the two app services.
cat > /etc/sudoers.d/afklive-deploy <<'EOF'
deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart afklive, /usr/bin/systemctl restart afklive-worker, /usr/bin/systemctl status afklive, /usr/bin/systemctl status afklive-worker
EOF
chmod 440 /etc/sudoers.d/afklive-deploy

# Jars are copied in by "deploy"; the services run as their own users in the afklive group.
install -d -m 2775 -o deploy -g afklive /opt/afklive /opt/afklive/worker
install -d -m 2770 -o afklive -g afklive /opt/afklive/data /opt/afklive/data/storage /opt/afklive/logs /opt/afklive/worker/data /opt/afklive/worker/logs
chown afklive-worker:afklive /opt/afklive/worker/data /opt/afklive/worker/logs
# Library files live in one place; the worker (working dir /opt/afklive/worker) sees the same folder.
[ -e /opt/afklive/worker/data/storage ] || ln -s /opt/afklive/data/storage /opt/afklive/worker/data/storage
install -d -m 755 -o afklive-ingest /opt/afklive/ingest

# --- secrets (generated once, on this server) ---------------------------------------------------------
ENV=/opt/afklive/afklive.env
rand() { openssl rand -base64 "$1" | tr -d '\n'; }
alnum() { openssl rand -hex "$1"; }
if [ ! -f "$ENV" ]; then
  DB_PASS=$(alnum 24)
  cat > "$ENV" <<EOF
# AFK Live settings. Generated $(date -Is). Back up APP_ENCRYPTION_KEY: without it stored keys can't be read.
APP_BASE_URL=https://$DOMAIN
APP_ENCRYPTION_KEY=$(rand 32)
REMEMBER_ME_KEY=$(alnum 32)
LIQUIBASE_CONTEXTS=prod

POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5432
POSTGRES_DB=afklive
POSTGRES_USER=afklive
POSTGRES_PASSWORD=$DB_PASS

# Library files on this server's disk (move to Cloudflare R2 later: STORAGE_TYPE=s3 + STORAGE_*; docs/operations.md#object-storage)
STORAGE_TYPE=local
STREAMING_WORKER_MAX_STREAMS=$MAX_STREAMS
STREAMING_CACHE_GB=20

# Phone streaming (MediaMTX on this server)
INGEST_PUBLIC_HOST=$INGEST_HOST
INGEST_RTMP_PORT=1935
INGEST_SRT_PORT=8890
INGEST_READ_URL=rtmp://127.0.0.1:1935/live/{key}?user={user}&pass={pass}
INGEST_READ_USER=worker
INGEST_READ_PASS=$(alnum 20)
INGEST_READ_ALLOWED=127.0.0.0/8,::1/128
INGEST_AUTH_SECRET=$(alnum 24)

# Fill these in (remove the "# "), then: sudo systemctl restart afklive afklive-worker
# An empty value overrides the app's default, so leave unused ones commented out.
# GOOGLE_CLIENT_ID=
# GOOGLE_CLIENT_SECRET=
# ZEPTOMAIL_URL=
# ZEPTOMAIL_TOKEN=
APP_EMAIL_SUPPORT=support@$DOMAIN
APP_EMAIL_NO_REPLY=no-reply@$DOMAIN
# PHONEPE_CLIENT_ID=
# PHONEPE_CLIENT_SECRET=
PHONEPE_ENV=SANDBOX
APP_BILLING_SELLER_NAME=AFK Live
# APP_BILLING_SELLER_ADDRESS=
# APP_BILLING_SELLER_GSTIN=
# APP_BILLING_SELLER_STATE=
EOF
  chown root:afklive "$ENV" && chmod 640 "$ENV"
fi
# Read values with grep, not by sourcing: the file is for systemd (unquoted spaces, & and {} in URLs).
env_val() { grep -m1 "^$1=" "$ENV" | cut -d= -f2-; }
# Push alerts on phones and computers (NT-1, MB-2): a VAPID key, added once (also to older env files).
if ! grep -q '^APP_PUSH_VAPID_KEY=' "$ENV"; then
  echo "APP_PUSH_VAPID_KEY=$(openssl ecparam -name prime256v1 -genkey -noout -outform DER | base64 -w0)" >> "$ENV"
fi
POSTGRES_PASSWORD=$(env_val POSTGRES_PASSWORD)
INGEST_AUTH_SECRET=$(env_val INGEST_AUTH_SECRET)

# --- PostgreSQL ---------------------------------------------------------------------------------------
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='afklive'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE ROLE afklive LOGIN PASSWORD '$POSTGRES_PASSWORD'"
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='afklive'" | grep -q 1 \
  || sudo -u postgres createdb -O afklive afklive
# Nightly dump, 14 kept (copy them off the server too).
install -d -m 750 -o postgres /var/backups/afklive
cat > /etc/cron.d/afklive-backup <<'EOF'
15 21 * * * postgres pg_dump -Fc afklive > /var/backups/afklive/afklive-$(date +\%F).dump && find /var/backups/afklive -name '*.dump' -mtime +14 -delete
EOF

# --- phone ingest (MediaMTX) ------------------------------------------------------------------------------
if [ ! -x /opt/afklive/ingest/mediamtx ]; then
  ARCH=$(dpkg --print-architecture); [ "$ARCH" = arm64 ] && MA=arm64v8 || MA=amd64
  curl -fsSL "https://github.com/bluenviron/mediamtx/releases/download/${MEDIAMTX_VERSION}/mediamtx_${MEDIAMTX_VERSION}_linux_${MA}.tar.gz" \
    | tar -xz -C /opt/afklive/ingest mediamtx
fi
cat > /opt/afklive/ingest/mediamtx.yml <<EOF
logLevel: info
authMethod: http
authHTTPAddress: http://127.0.0.1:8080/internal/ingest/auth?secret=$INGEST_AUTH_SECRET
authHTTPExclude: []
api: no
metrics: no
pprof: no
playback: no
rtmp: yes
rtmpAddress: :1935
srt: yes
srtAddress: :8890
rtsp: no
hls: no
webrtc: no
paths:
  "~^live/[A-Za-z0-9]{8,64}\$":
    source: publisher
    overridePublisher: yes
EOF
chown afklive-ingest /opt/afklive/ingest/mediamtx.yml && chmod 600 /opt/afklive/ingest/mediamtx.yml

# --- services ----------------------------------------------------------------------------------------------
cat > /etc/systemd/system/afklive.service <<'EOF'
[Unit]
Description=AFK Live web app
After=network-online.target postgresql.service
Wants=network-online.target
ConditionPathExists=/opt/afklive/app.jar

[Service]
User=afklive
Group=afklive
UMask=0007
WorkingDirectory=/opt/afklive
EnvironmentFile=/opt/afklive/afklive.env
ExecStart=/usr/bin/java -Xmx1500m -Dapp.streaming.role=web -Dserver.address=127.0.0.1 -jar /opt/afklive/app.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
cat > /etc/systemd/system/afklive-worker.service <<'EOF'
[Unit]
Description=AFK Live stream worker
After=network-online.target postgresql.service
Wants=network-online.target
ConditionPathExists=/opt/afklive/worker/app.jar

[Service]
User=afklive-worker
Group=afklive
UMask=0007
WorkingDirectory=/opt/afklive/worker
EnvironmentFile=/opt/afklive/afklive.env
ExecStart=/usr/bin/java -Xmx768m -Dspring.profiles.active=worker -Dapp.streaming.role=worker -Dapp.streaming.worker-id=%H -jar /opt/afklive/worker/app.jar
TimeoutStopSec=20
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
cat > /etc/systemd/system/afklive-ingest.service <<'EOF'
[Unit]
Description=AFK Live ingest server (MediaMTX)
After=network-online.target
Wants=network-online.target

[Service]
User=afklive-ingest
WorkingDirectory=/opt/afklive/ingest
ExecStart=/opt/afklive/ingest/mediamtx /opt/afklive/ingest/mediamtx.yml
Restart=always
RestartSec=2
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable afklive afklive-worker afklive-ingest
systemctl restart afklive-ingest

# --- HTTPS in front of the web app ------------------------------------------------------------------------------
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN, www.$DOMAIN {
    @www host www.$DOMAIN
    redir @www https://$DOMAIN{uri} permanent
    encode zstd gzip
    request_body {
        max_size 10GB
    }
    reverse_proxy 127.0.0.1:8080
}
EOF
systemctl reload caddy || systemctl restart caddy

# --- worker egress firewall (FFmpeg can't reach private addresses except what it needs) ---------------------------
cat > /etc/nftables.d-afklive-worker.nft <<'EOF'
table inet afklive_worker_egress {
    chain output {
        type filter hook output priority 0; policy accept;
        meta skuid "afklive-worker" ip daddr 127.0.0.1 tcp dport 5432 accept
        meta skuid "afklive-worker" ip daddr 127.0.0.1 tcp dport 1935 accept
        meta skuid "afklive-worker" ip daddr 127.0.0.53 udp dport 53 accept
        meta skuid "afklive-worker" ip daddr 127.0.0.53 tcp dport 53 accept
        meta skuid "afklive-worker" ct state established,related accept
        meta skuid "afklive-worker" ip daddr { 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16, 224.0.0.0/3 } reject
        meta skuid "afklive-worker" ip6 daddr { ::1, fc00::/7, fe80::/10, 64:ff9b::/96 } reject
    }
}
EOF
# Loaded by its own unit (not nftables.service, whose config flushes every table, ufw's included).
cat > /etc/systemd/system/afklive-egress.service <<'EOF'
[Unit]
Description=AFK Live worker egress firewall
After=network-pre.target
Before=afklive-worker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStartPre=-/usr/sbin/nft delete table inet afklive_worker_egress
ExecStart=/usr/sbin/nft -f /etc/nftables.d-afklive-worker.nft
ExecStop=/usr/sbin/nft delete table inet afklive_worker_egress

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now afklive-egress

# --- inbound firewall ------------------------------------------------------------------------------------------------
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw allow 1935/tcp
ufw allow 8890/udp
ufw --force enable

echo "=== AFK Live setup done $(date -Is). Next: DNS, GitHub secrets, deploy. ==="
