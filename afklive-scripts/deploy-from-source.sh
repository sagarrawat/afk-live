#!/bin/bash
# Builds AFK Live on the server from a branch and installs it (web app + stream worker).
# For when GitHub Actions can't deploy (e.g. secrets not set yet). Run as root on a server set up
# with provision-single-server.sh:
#   curl -fsSL https://raw.githubusercontent.com/sagarrawat/afk-live/main/afklive-scripts/deploy-from-source.sh | bash -s -- main
set -euo pipefail
BRANCH="${1:-main}"
exec > >(tee -a /var/log/afklive-deploy.log) 2>&1
echo "=== Deploy $BRANCH $(date -Is) ==="

export DEBIAN_FRONTEND=noninteractive
command -v mvn >/dev/null || apt-get install -y maven git openjdk-21-jdk-headless
if ! command -v node >/dev/null || [ "$(node -v | cut -c2- | cut -d. -f1)" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

SRC=/opt/afklive-src
if [ -d "$SRC/.git" ]; then
  git -C "$SRC" fetch --depth 1 origin "$BRANCH" && git -C "$SRC" checkout -q -f FETCH_HEAD
else
  git clone -q --depth 1 -b "$BRANCH" https://github.com/sagarrawat/afk-live "$SRC"
fi
cd "$SRC"
echo "Building $(git rev-parse --short HEAD)"
mvn -B -q package -pl afklive-web -am -DskipTests -DskipUiTests

if [ -x /opt/afklive/scripts/web-deploy.sh ]; then
  # OPS-4 is set up: the web app switches slots without downtime (see docs/operations.md).
  /opt/afklive/scripts/web-deploy.sh afklive-web/target/app.jar
  install -m 640 -o deploy -g afklive afklive-web/target/app.jar /opt/afklive/worker/app.jar
  systemctl restart afklive-worker
  echo "=== Deployed $(git rev-parse --short HEAD) ==="
  exit 0
fi
install -m 640 -o deploy -g afklive afklive-web/target/app.jar /opt/afklive/app.jar
install -m 640 -o deploy -g afklive afklive-web/target/app.jar /opt/afklive/worker/app.jar
systemctl restart afklive
systemctl restart afklive-worker

echo "Waiting for the web app..."
for i in $(seq 1 60); do
  if curl -fsS -o /dev/null http://127.0.0.1:8080/; then
    echo "=== Deployed $(git rev-parse --short HEAD): web app is up ==="
    systemctl --no-pager --lines=0 status afklive afklive-worker afklive-ingest caddy | grep -E '●|Active:'
    exit 0
  fi
  sleep 3
done
echo "!!! The web app didn't answer within 3 minutes. Last log lines:"
journalctl -u afklive -n 40 --no-pager
exit 1
