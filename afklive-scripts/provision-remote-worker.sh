#!/usr/bin/env bash
# FO-8: set up an extra stream worker on another provider (Oracle Cloud Always Free ARM, Hetzner, ...).
# Ubuntu 24.04, arm64 or amd64. Run once as root on the new server; safe to re-run (keeps keys and settings).
#
#   sudo provision-remote-worker.sh --pool playout \
#        --main-wg-endpoint 203.0.113.10:51820 --main-wg-pubkey 'BASE64KEY=' \
#        [--wg-address 10.77.0.2/24] [--main-wg-ip 10.77.0.1] [--max-streams 4] \
#        [--deploy-key 'ssh-ed25519 AAAA... afklive-deploy'] [--dry-run]
#
# What it does (nothing else; it never starts a worker and never touches the main server):
#   1. Installs Java 21 (headless JRE), FFmpeg (with libx264), WireGuard and nftables from Ubuntu.
#   2. Creates the afklive-worker user (the egress firewall applies to it) and, with --deploy-key, the
#      "deploy" user the worker deploy workflow logs in as (it may run only the handover script with sudo).
#   3. WireGuard client wg-afklive: a private tunnel to the main server, which is how this worker reaches
#      PostgreSQL (and Loki) without exposing them to the internet. Prints this server's public key: add
#      it as a peer on the main server (docs/operations.md, "Worker pools").
#   4. /opt/afklive/afklive.env with this worker's pool and the database over the tunnel. Secrets are left
#      empty: copy them from the main server's afklive.env (never commit them, never paste them in a chat).
#   5. The worker slot units (afklive-worker@a/b) and the handover deploy script, as on the main server.
#   6. The worker egress firewall: the worker user can't reach private, loopback or cloud-metadata
#      addresses except the database and Loki over the tunnel and the local DNS resolver.
# Then: fill in the secrets, add the peer on the main server, and deploy the worker (docs/operations.md).
#
# --dry-run prints every change instead of making it (no root needed). ROOT=/some/dir runs against a copy
# of the file system (tests).
set -euo pipefail

ROOT=${ROOT:-}
SCRIPT_DIR=${SCRIPT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}
OS_RELEASE=${OS_RELEASE:-$ROOT/etc/os-release}
WG_IF=wg-afklive

DRY_RUN=0
POOL=""
MAIN_WG_ENDPOINT=""
MAIN_WG_PUBKEY=""
WG_ADDRESS=10.77.0.2/24
MAIN_WG_IP=10.77.0.1
MAX_STREAMS=4
DEPLOY_KEY=""

log() { printf '%s\n' "$*" >&2; }
die() { log "ERROR: $1"; exit "${2:-1}"; }
usage() { die "usage: $0 --pool NAME --main-wg-endpoint HOST:PORT --main-wg-pubkey KEY [--wg-address CIDR] [--main-wg-ip IP] [--max-streams N] [--deploy-key KEY] [--dry-run]" 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --pool) POOL=${2:-}; shift 2 || usage ;;
    --main-wg-endpoint) MAIN_WG_ENDPOINT=${2:-}; shift 2 || usage ;;
    --main-wg-pubkey) MAIN_WG_PUBKEY=${2:-}; shift 2 || usage ;;
    --wg-address) WG_ADDRESS=${2:-}; shift 2 || usage ;;
    --main-wg-ip) MAIN_WG_IP=${2:-}; shift 2 || usage ;;
    --max-streams) MAX_STREAMS=${2:-}; shift 2 || usage ;;
    --deploy-key) DEPLOY_KEY=${2:-}; shift 2 || usage ;;
    *) usage ;;
  esac
done

[ -n "$POOL" ] && [ -n "$MAIN_WG_ENDPOINT" ] && [ -n "$MAIN_WG_PUBKEY" ] || usage
[[ "$POOL" =~ ^[a-z0-9][a-z0-9_-]{0,39}$ ]] || die "--pool: 1-40 lowercase letters, digits, '-' or '_'" 2
[ "$POOL" != main ] || die "--pool main is the main server's pool; give remote workers their own (e.g. playout)" 2
[[ "$MAIN_WG_ENDPOINT" =~ ^[A-Za-z0-9.:-]+:[0-9]+$ ]] || die "--main-wg-endpoint: HOST:PORT, e.g. 203.0.113.10:51820" 2
[[ "$MAIN_WG_PUBKEY" =~ ^[A-Za-z0-9+/]{42,44}=?$ ]] || die "--main-wg-pubkey: the main server's WireGuard public key (wg pubkey)" 2
[[ "$WG_ADDRESS" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+$ ]] || die "--wg-address: an IPv4 CIDR, e.g. 10.77.0.2/24" 2
[[ "$MAIN_WG_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "--main-wg-ip: an IPv4 address" 2
[[ "$MAX_STREAMS" =~ ^[1-9][0-9]*$ ]] || die "--max-streams: a positive number" 2

if [ "$DRY_RUN" != 1 ] && [ "$(id -u)" -ne 0 ]; then
  die "run with sudo (or --dry-run to see what it would do)"
fi

# Ubuntu 24.04 on arm64 or amd64 only: the packages and the unit are tested there.
# shellcheck disable=SC1090
OS=$( . "$OS_RELEASE" 2>/dev/null && echo "${ID:-}-${VERSION_ID:-}" ) || OS=unknown
[ "$OS" = ubuntu-24.04 ] || die "this script supports Ubuntu 24.04 only (found $OS)" 3
ARCH=${ARCH:-$(dpkg --print-architecture)}
case "$ARCH" in
  arm64|amd64) ;;
  *) die "unsupported architecture $ARCH (arm64 or amd64)" 3 ;;
esac
log "Remote worker for pool '$POOL' on Ubuntu 24.04 $ARCH$([ "$DRY_RUN" = 1 ] && echo ' (dry run: nothing changes)')"

# Runs a command that changes something, or only prints it with --dry-run.
run() {
  if [ "$DRY_RUN" = 1 ]; then log "[dry-run] $*"; else "$@"; fi
}

# write PATH MODE OWNER:GROUP < content. Never overwrites a file that exists unless FORCE=1.
write() {
  local path=$ROOT$1 mode=$2 owner=$3 content
  content=$(cat)
  if [ -e "$path" ] && [ "${FORCE:-0}" != 1 ]; then
    log "keeping $1 (exists)"
    return 0
  fi
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] write $1 ($mode $owner)"
    return 0
  fi
  mkdir -p "$(dirname "$path")"
  printf '%s\n' "$content" > "$path.new"
  chmod "$mode" "$path.new"
  [ -n "$ROOT" ] || chown "$owner" "$path.new"
  mv -f "$path.new" "$path"
  log "wrote $1"
}

dir() { # PATH MODE OWNER:GROUP
  if [ "$DRY_RUN" = 1 ]; then log "[dry-run] mkdir $1 ($2 $3)"; return 0; fi
  mkdir -p "$ROOT$1"
  chmod "$2" "$ROOT$1"
  [ -n "$ROOT" ] || chown "$3" "$ROOT$1"
}

# --- 1. packages --------------------------------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
run apt-get update -y
run apt-get install -y openjdk-21-jre-headless ffmpeg wireguard-tools nftables curl unattended-upgrades
if [ "$DRY_RUN" != 1 ]; then
  ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libx264 \
    || die "this FFmpeg has no libx264 encoder; the worker needs it (Ubuntu's ffmpeg package has it)" 1
fi

# --- 2. users -----------------------------------------------------------------------------------------
getent group afklive >/dev/null || run groupadd afklive
id afklive-worker >/dev/null 2>&1 || run useradd --system --gid afklive --home /opt/afklive/worker --shell /usr/sbin/nologin afklive-worker
dir /opt/afklive 2775 root:afklive
dir /opt/afklive/worker 2775 root:afklive
dir /opt/afklive/worker/data 2770 afklive-worker:afklive
dir /opt/afklive/worker/logs 2770 afklive-worker:afklive
dir /opt/afklive/worker/slots/a 2775 root:afklive
dir /opt/afklive/worker/slots/b 2775 root:afklive
dir /opt/afklive/scripts 0755 root:root
if [ -n "$DEPLOY_KEY" ]; then
  id deploy >/dev/null 2>&1 || run useradd --create-home --shell /bin/bash --groups afklive deploy
  dir /home/deploy/.ssh 0700 deploy:deploy
  write /home/deploy/.ssh/authorized_keys 0600 deploy:deploy <<<"$DEPLOY_KEY"
  dir /opt/afklive/worker/incoming 2775 deploy:afklive
  write /etc/sudoers.d/afklive-worker-deploy 0440 root:root <<'EOF'
deploy ALL=(root) NOPASSWD: /opt/afklive/scripts/worker-handover-deploy.sh
EOF
fi
# A static FFmpeg copied from an x86 server would shadow the system one (the app prefers ./bin/ffmpeg).
if [ -e "$ROOT/opt/afklive/worker/bin/ffmpeg" ]; then
  log "WARNING: /opt/afklive/worker/bin/ffmpeg exists and wins over the system FFmpeg; remove it unless it is built for $ARCH"
fi

# --- 3. WireGuard tunnel to the main server --------------------------------------------------------------
KEY_FILE=/etc/wireguard/afklive.key
if [ ! -e "$ROOT$KEY_FILE" ]; then
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] wg genkey > $KEY_FILE (0600)"
  else
    mkdir -p "$ROOT/etc/wireguard"
    (umask 077; wg genkey > "$ROOT$KEY_FILE")
  fi
fi
if [ "$DRY_RUN" = 1 ]; then
  PRIVATE_KEY='(generated on the server)'
  PUBLIC_KEY='(printed after a real run)'
else
  PRIVATE_KEY=$(cat "$ROOT$KEY_FILE")
  PUBLIC_KEY=$(wg pubkey < "$ROOT$KEY_FILE")
fi
write /etc/wireguard/$WG_IF.conf 0600 root:root <<EOF
# FO-8: tunnel to the main AFK Live server (database, Loki). Written by provision-remote-worker.sh.
[Interface]
Address = $WG_ADDRESS
PrivateKey = $PRIVATE_KEY

[Peer]
# The main server
PublicKey = $MAIN_WG_PUBKEY
Endpoint = $MAIN_WG_ENDPOINT
AllowedIPs = $MAIN_WG_IP/32
PersistentKeepalive = 25
EOF
run systemctl enable --now wg-quick@$WG_IF

# --- 4. settings ----------------------------------------------------------------------------------------
if [ "$DRY_RUN" = 1 ]; then CONTROL_TOKEN='(generated)'; else CONTROL_TOKEN=$(openssl rand -hex 32); fi
write /opt/afklive/afklive.env 0640 root:afklive <<EOF
# AFK Live remote worker ($POOL pool). Generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by provision-remote-worker.sh.
# Fill in the empty secrets from the main server's /opt/afklive/afklive.env (same values), then deploy.
STREAMING_WORKER_POOL=$POOL
# Same on every worker: phone streams only on pools that can read the ingest server; playlists prefer this pool.
STREAMING_PHONE_POOLS=main
STREAMING_POOL_PREFERENCE=PLAYOUT=$POOL
STREAMING_WORKER_MAX_STREAMS=$MAX_STREAMS
WORKER_CONTROL_TOKEN=$CONTROL_TOKEN

# Database over the WireGuard tunnel (never over the open internet)
POSTGRES_HOST=$MAIN_WG_IP
POSTGRES_PORT=5432
POSTGRES_DB=afklive
POSTGRES_USER=afklive
POSTGRES_PASSWORD=
# Decrypts stream keys: the same key as the main server. This server must be as trusted as the main one.
APP_ENCRYPTION_KEY=
APP_ENCRYPTION_OLD_KEYS=

# Library files from object storage (DigitalOcean Spaces or Cloudflare R2); a remote worker can't use local storage
STORAGE_TYPE=s3
DO_SPACES_ENDPOINT=
DO_SPACES_REGION=
DO_SPACES_BUCKET=
DO_SPACES_KEY=
DO_SPACES_SECRET=

# Same encoder settings as the main worker (profile copies are made for this preset)
APP_STREAMING_X264PRESET=superfast
APP_STREAMING_COPYMODE=false

# Logs to the main server's Loki over the tunnel (Loki must listen on $MAIN_WG_IP:3100)
LOKI_URL=http://$MAIN_WG_IP:3100/loki/api/v1/push
EOF
write /opt/afklive/worker-slot-a.env 0644 root:afklive <<<"WORKER_HTTP_PORT=8081"
write /opt/afklive/worker-slot-b.env 0644 root:afklive <<<"WORKER_HTTP_PORT=8082"

# --- 5. units and the deploy script ------------------------------------------------------------------------
for f in "$SCRIPT_DIR/systemd/afklive-worker@.service" "$SCRIPT_DIR/worker-handover-deploy.sh"; do
  [ -f "$f" ] || die "missing $f (run this script from a checkout of afklive-scripts)" 1
done
run install -D -m 0644 "$SCRIPT_DIR/systemd/afklive-worker@.service" "$ROOT/etc/systemd/system/afklive-worker@.service"
run install -D -m 0755 "$SCRIPT_DIR/worker-handover-deploy.sh" "$ROOT/opt/afklive/scripts/worker-handover-deploy.sh"
run systemctl daemon-reload

# --- 6. egress firewall for the worker user ------------------------------------------------------------------
FORCE=1 write /etc/nftables.d/afklive-worker-egress.nft 0644 root:root <<EOF
# FO-8: egress firewall for the stream worker user on a remote worker (see afklive-scripts/worker-egress.nft).
# FFmpeg resolves destinations again when it connects; this keeps it off private, loopback and cloud-metadata
# addresses. Allowed: the database and Loki on the main server over the tunnel, and the local DNS resolver.
# Platforms (RTMP/RTMPS/SRT) and object storage are public addresses and stay allowed.
table inet afklive_worker_egress {
    chain output {
        type filter hook output priority 0; policy accept;

        meta skuid "afklive-worker" ip daddr $MAIN_WG_IP tcp dport 5432 accept    # PostgreSQL over WireGuard
        meta skuid "afklive-worker" ip daddr $MAIN_WG_IP tcp dport 3100 accept    # Loki over WireGuard
        meta skuid "afklive-worker" ip daddr 127.0.0.53 udp dport 53 accept      # systemd-resolved (DNS)
        meta skuid "afklive-worker" ip daddr 127.0.0.53 tcp dport 53 accept
        meta skuid "afklive-worker" ct state established,related accept

        meta skuid "afklive-worker" ip daddr { 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16, 224.0.0.0/3 } reject
        meta skuid "afklive-worker" ip6 daddr { ::1, fc00::/7, fe80::/10, 64:ff9b::/96 } reject
    }
}
EOF
NFT_CONF=$ROOT/etc/nftables.conf
if ! grep -qs 'afklive-worker-egress.nft' "$NFT_CONF"; then
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] add include \"/etc/nftables.d/afklive-worker-egress.nft\" to /etc/nftables.conf"
  else
    printf '\ninclude "/etc/nftables.d/afklive-worker-egress.nft"\n' >> "$NFT_CONF"
  fi
fi
run nft -f "$ROOT/etc/nftables.d/afklive-worker-egress.nft"
run systemctl enable nftables

log ""
log "Done. Next steps (docs/operations.md, \"Worker pools\"):"
log "  1. On the main server, add this peer to /etc/wireguard/wg-afklive.conf and reload it:"
log "       [Peer]"
log "       PublicKey = $PUBLIC_KEY"
log "       AllowedIPs = ${WG_ADDRESS%/*}/32"
log "  2. Fill in the empty secrets in /opt/afklive/afklive.env (same values as the main server)."
log "  3. Deploy the worker to this host (Actions > Deploy stream worker, with this host in WORKER_HOSTS)."
