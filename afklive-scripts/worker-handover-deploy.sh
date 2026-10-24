#!/usr/bin/env bash
# OPS-3: deploy a new stream-worker version without interrupting live streams.
#
#   sudo worker-handover-deploy.sh NEW_APP_JAR            # hand streams over to the new version (default)
#   sudo worker-handover-deploy.sh --restart NEW_APP_JAR  # old way: restart the worker (streams drop ~30-90 s)
#   sudo worker-handover-deploy.sh --status               # which slot runs, and what it owns
#
# Handover: the new version starts in the idle systemd slot (afklive-worker@a / @b, each with its own
# worker id and localhost port) next to the running one. Once it is ready, the running worker is asked
# to drain (recorded in the database): it takes on nothing new and offers its streams one at a time; the
# new worker starts each at the same playlist position, publishes YouTube to the other ingest, and when
# it is on air everywhere the old one stops its copy. When the old worker owns nothing, it is stopped
# and the new slot becomes the one that starts at boot.
#
# Safe exits: before the drain nothing has changed for any stream (exit 1 / 3 / 4). If the new worker
# dies during the drain, the old one is told to stop draining and keeps its remaining streams (exit 1).
# If some streams haven't moved after the time limit, the old worker is stopped anyway: it lets go of
# them and the new worker claims them within seconds (a short reconnect for those only; exit 2).
#
# The first deploy that migrates from the single afklive-worker.service also works: that worker is
# treated as the running one (its port is WORKER_HTTP_PORT, default 8081) and the new version goes to
# slot b. The running worker must already be a version with the handover (this script checks).
# One-time server setup: docs/operations.md ("Worker deploys without interruption").
set -euo pipefail

ENV_FILE=${ENV_FILE:-/opt/afklive/afklive.env}
WORKER_DIR=${WORKER_DIR:-/opt/afklive/worker}
SLOT_ENV_DIR=${SLOT_ENV_DIR:-/opt/afklive}
LEGACY_UNIT=afklive-worker.service
READY_TIMEOUT=${READY_TIMEOUT:-240}          # seconds for the new JVM to start and finish its first pass
PER_STREAM_SECONDS=${PER_STREAM_SECONDS:-90} # drain time budget per owned stream (a handover takes ~15-30 s)
MIN_DRAIN_SECONDS=${MIN_DRAIN_SECONDS:-300}
MIN_FREE_MB=${MIN_FREE_MB:-1200}             # a second JVM (-Xmx512m, ~700 MB resident) plus one extra encode

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; } # stderr: also visible from inside $(...)
die() { log "ERROR: $*"; exit "${2:-1}"; }

[ "$(id -u)" -eq 0 ] || die "run with sudo"

token() {
  local t
  t=$(grep -E '^WORKER_CONTROL_TOKEN=' "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '"'"'"' ')
  [ -n "$t" ] || die "WORKER_CONTROL_TOKEN is not set in $ENV_FILE (see docs/operations.md)" 4
  printf '%s' "$t"
}

unit_of() { if [ "$1" = legacy ]; then echo "$LEGACY_UNIT"; else echo "afklive-worker@$1.service"; fi; }

port_of() {
  local f p
  if [ "$1" = legacy ]; then f=$ENV_FILE; else f=$SLOT_ENV_DIR/worker-slot-$1.env; fi
  p=$(grep -E '^WORKER_HTTP_PORT=' "$f" 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '"'"'"' ')
  if [ -z "$p" ]; then
    if [ "$1" = legacy ]; then p=8081; else die "WORKER_HTTP_PORT missing in $f" 4; fi
  fi
  printf '%s' "$p"
}

active() { systemctl is-active --quiet "$(unit_of "$1")"; }

# GET or POST /internal/worker/<what> on a slot; prints the JSON body, fails on anything but 200.
call() {
  local slot=$1 method=$2 what=$3
  curl -fsS --max-time 10 -X "$method" -H "X-Worker-Token: $TOKEN" "http://127.0.0.1:$(port_of "$slot")/internal/worker/$what"
}

field() { sed -n "s/.*\"$2\":\([^,}]*\).*/\1/p" <<<"$1" | tr -d '"'; }

running_slot() {
  local found=()
  for s in a b; do active "$s" && found+=("$s"); done
  if [ "${#found[@]}" -gt 1 ]; then
    die "both slots are running (an earlier deploy didn't finish). Check 'worker-handover-deploy.sh --status', stop the one that owns nothing, then retry" 3
  fi
  if [ "${#found[@]}" -eq 1 ]; then
    active legacy && die "slot ${found[0]} and $LEGACY_UNIT are both running; stop the legacy unit first" 3
    echo "${found[0]}"
  elif active legacy; then
    echo legacy
  else
    echo none
  fi
}

install_jar() { # jar slot
  local dest
  if [ "$2" = legacy ]; then dest=$WORKER_DIR/app.jar; else dest=$WORKER_DIR/slots/$2/app.jar; fi
  mkdir -p "$(dirname "$dest")"
  install -m 0644 "$1" "$dest.new"
  mv -f "$dest.new" "$dest" # atomic: a running JVM keeps its open file
  log "installed $(basename "$1") as $dest"
}

status() {
  local s
  s=$(running_slot)
  echo "running: $s"
  [ "$s" = none ] && return 0
  call "$s" GET status || echo "(control endpoint not answering on port $(port_of "$s"))"
  echo
}

restart() { # jar
  local s
  s=$(running_slot)
  if [ "$s" = none ]; then
    if systemctl cat afklive-worker@.service >/dev/null 2>&1 && [ -f "$SLOT_ENV_DIR/worker-slot-a.env" ]; then s=a; else s=legacy; fi
  fi
  install_jar "$1" "$s"
  log "restarting $(unit_of "$s"): its streams drop and resume when it is back (about 30-90 s)"
  systemctl restart "$(unit_of "$s")"
  if [ "$s" != legacy ]; then systemctl enable --quiet "$(unit_of "$s")"; fi
  log "done"
}

handover() { # jar
  local jar=$1 old new owned deadline st
  old=$(running_slot)
  [ "$old" = none ] && die "no worker is running; use --restart to start one" 3
  if [ "$old" = b ]; then new=a; else new=b; fi
  for s in a b; do [ -f "$SLOT_ENV_DIR/worker-slot-$s.env" ] || die "missing $SLOT_ENV_DIR/worker-slot-$s.env (one-time setup)" 4; done
  systemctl cat afklive-worker@.service >/dev/null 2>&1 || die "afklive-worker@.service is not installed (one-time setup)" 4

  # The running worker must support handovers (and know the token) before anything changes.
  st=$(call "$old" GET status) || die "the running worker ($(unit_of "$old")) doesn't answer /internal/worker/status: it predates the handover or WORKER_CONTROL_TOKEN differs. Deploy once with --restart" 4
  owned=$(field "$st" owned)
  log "running: $(unit_of "$old") as $(field "$st" workerId), $owned streams; new version goes to slot $new"

  local avail_mb
  avail_mb=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
  if [ "$avail_mb" -lt "$MIN_FREE_MB" ]; then
    die "only ${avail_mb} MB memory available; a second worker needs about ${MIN_FREE_MB} MB. Free memory or use --restart" 3
  fi

  install_jar "$jar" "$new"
  log "starting $(unit_of "$new")"
  systemctl start "$(unit_of "$new")"
  deadline=$((SECONDS + READY_TIMEOUT))
  until st=$(call "$new" GET status 2>/dev/null) && [ "$(field "$st" ready)" = true ]; do
    if ! active "$new"; then
      journalctl -u "$(unit_of "$new")" -n 30 --no-pager || true
      die "the new worker exited while starting; the old one was never touched" 1
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      systemctl stop "$(unit_of "$new")" || true
      die "the new worker wasn't ready within ${READY_TIMEOUT}s; stopped it, the old one was never touched" 1
    fi
    sleep 3
  done
  log "new worker $(field "$st" workerId) is ready"

  call "$old" POST drain >/dev/null || { systemctl stop "$(unit_of "$new")" || true; die "couldn't ask the old worker to drain; stopped the new one" 1; }
  local budget=$((owned * PER_STREAM_SECONDS))
  [ "$budget" -lt "$MIN_DRAIN_SECONDS" ] && budget=$MIN_DRAIN_SECONDS
  deadline=$((SECONDS + budget))
  log "old worker is draining: handing over $owned streams, one at a time (up to ${budget}s)"
  local left=$owned last=-1
  while :; do
    if ! active "$new"; then
      call "$old" POST undrain >/dev/null || true
      journalctl -u "$(unit_of "$new")" -n 30 --no-pager || true
      die "the new worker stopped during the handover; the old one stopped draining and keeps its streams" 1
    fi
    if st=$(call "$old" GET status 2>/dev/null); then
      left=$(field "$st" owned)
      local here; here=$(field "$st" running)
      if [ "$left" != "$last" ]; then log "old worker still owns $left (running $here)"; last=$left; fi
      [ "$left" = 0 ] && [ "$here" = 0 ] && break
    elif ! active "$old"; then
      log "the old worker exited during the handover; the new one takes over what it had"
      break
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      log "WARNING: $left streams not handed over within ${budget}s; stopping the old worker anyway (they reconnect on the new one within seconds)"
      break
    fi
    sleep 5
  done

  log "stopping $(unit_of "$old")"
  systemctl stop "$(unit_of "$old")" || true
  systemctl disable --quiet "$(unit_of "$old")" || true
  systemctl enable --quiet "$(unit_of "$new")"
  st=$(call "$new" GET status || true)
  log "done: $(unit_of "$new") runs $(field "$st" running) streams"
  [ "$left" = 0 ] || exit 2
}

case "${1:-}" in
  --status) TOKEN=$(token); status ;;
  --restart) [ -f "${2:-}" ] || die "usage: $0 --restart NEW_APP_JAR"; restart "$2" ;;
  -*|"") die "usage: $0 [--restart] NEW_APP_JAR | --status" ;;
  *) [ -f "$1" ] || die "no such jar: $1"; TOKEN=$(token); handover "$1" ;;
esac
