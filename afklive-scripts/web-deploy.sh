#!/usr/bin/env bash
# OPS-4: deploy a new web-app version with no downtime (blue/green on one server).
#
#   sudo web-deploy.sh NEW_APP_JAR              # start it next to the running version, then stop the old one (default)
#   sudo web-deploy.sh --restart NEW_APP_JAR    # old way: restart the running version (about a minute of errors)
#   sudo web-deploy.sh --rollback               # go back to the previous version (still installed in the idle slot)
#   web-deploy.sh --status                      # which slot runs and whether it is ready
#   web-deploy.sh --dry-run [...]               # print what would happen; changes nothing (no sudo needed)
#
# Two systemd slots of the web app, afklive-web@a and @b, each on its own localhost port (8080 / 8090, set in
# /opt/afklive/web-slot-a.env and -b.env), share one database; normally only one runs. Caddy proxies to both
# ports with an active health check on /actuator/health/readiness and sends requests only to a ready slot,
# retrying a refused connection on the other. A deploy:
#   1. installs the jar for the idle slot and starts it; the running slot keeps serving;
#   2. waits until the new slot reports ready (startup and Liquibase migrations included, about 70 s);
#   3. waits a few seconds so Caddy's health check (every second) sees it;
#   4. stops the old slot: it reports "not ready" for 5 s so Caddy moves new requests, then finishes the
#      requests in flight (graceful shutdown) and exits. The new slot becomes the one that starts at boot.
# If the new version never gets ready it is stopped and the old one keeps serving (exit 1).
#
# Refuses (exit 3, nothing changed) when a worker deploy is running (both worker slots up: never run web and
# worker deploys at once), when both web slots are running (an earlier deploy didn't finish), or when less
# than MIN_FREE_MB of memory is available for the second JVM. Exit 4: the one-time setup is missing.
#
# The first deploy from the single afklive.service also works: that unit is treated as the running version
# (port 8080) and the new version goes to slot b. One-time server setup: docs/operations.md
# ("Web deploys without downtime").
set -euo pipefail

ENV_DIR=${ENV_DIR:-/opt/afklive}                 # web-slot-a.env / web-slot-b.env
WEB_DIR=${WEB_DIR:-/opt/afklive/web}             # slots/<slot>/app.jar
LEGACY_UNIT=afklive.service
LEGACY_JAR=${LEGACY_JAR:-/opt/afklive/app.jar}
LEGACY_PORT=${LEGACY_PORT:-8080}
READY_TIMEOUT=${READY_TIMEOUT:-300}              # seconds for the new JVM to start and run its migrations
SETTLE_SECONDS=${SETTLE_SECONDS:-3}              # Caddy checks health every second
MIN_FREE_MB=${MIN_FREE_MB:-1800}                 # a second web JVM: -Xmx1g heap plus ~0.5 GB off-heap, and margin
MEMINFO=${MEMINFO:-/proc/meminfo}
LOCK_FILE=${LOCK_FILE:-/run/afklive-web-deploy.lock}

DRY_RUN=0

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; } # stderr: also visible from inside $(...)
die() { log "ERROR: $*"; exit "${2:-1}"; }

# Runs a command that changes something, or only prints it with --dry-run.
run() {
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] $*"
  else
    "$@"
  fi
}

unit_of() { if [ "$1" = legacy ]; then echo "$LEGACY_UNIT"; else echo "afklive-web@$1.service"; fi; }
active() { systemctl is-active --quiet "$(unit_of "$1")"; }
other() { if [ "$1" = a ]; then echo b; else echo a; fi; }
jar_of() { if [ "$1" = legacy ]; then echo "$LEGACY_JAR"; else echo "$WEB_DIR/slots/$1/app.jar"; fi; }

port_of() {
  local p
  if [ "$1" = legacy ]; then
    echo "$LEGACY_PORT"
    return
  fi
  p=$(grep -E '^WEB_HTTP_PORT=' "$ENV_DIR/web-slot-$1.env" 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '"'"'"' ')
  [ -n "$p" ] || die "WEB_HTTP_PORT missing in $ENV_DIR/web-slot-$1.env (one-time setup)" 4
  echo "$p"
}

ready() { curl -fsS --max-time 3 -o /dev/null "http://127.0.0.1:$(port_of "$1")/actuator/health/readiness" 2>/dev/null; }

set_up() {
  systemctl cat afklive-web@.service >/dev/null 2>&1 || die "afklive-web@.service is not installed (one-time setup in docs/operations.md)" 4
  for s in a b; do
    [ -f "$ENV_DIR/web-slot-$s.env" ] || die "missing $ENV_DIR/web-slot-$s.env (one-time setup)" 4
    port_of "$s" >/dev/null
  done
  [ "$(port_of a)" != "$(port_of b)" ] || die "both web slots use port $(port_of a)" 4
}

running_slot() {
  local found=() s
  for s in a b; do active "$s" && found+=("$s"); done
  if [ "${#found[@]}" -gt 1 ]; then
    die "both web slots are running (an earlier deploy didn't finish). Check '$0 --status', stop the one Caddy shouldn't use, then retry" 3
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

# Never next to a worker deploy: both would need a second JVM (and the handover extra encodes) at once.
no_worker_deploy() {
  if systemctl is-active --quiet afklive-worker@a.service && systemctl is-active --quiet afklive-worker@b.service; then
    die "a worker deploy is running (both worker slots are up). Never run web and worker deploys at the same time; retry when it has finished" 3
  fi
}

enough_memory() {
  local avail_mb
  avail_mb=$(awk '/^MemAvailable:/ {print int($2/1024)}' "$MEMINFO")
  [ -n "$avail_mb" ] || die "can't read MemAvailable from $MEMINFO" 3
  if [ "$avail_mb" -lt "$MIN_FREE_MB" ]; then
    die "only ${avail_mb} MB memory available; a second web app needs about ${MIN_FREE_MB} MB. Nothing was changed. Free memory, or deploy with --restart (about a minute of errors)" 3
  fi
  log "memory available: ${avail_mb} MB (need ${MIN_FREE_MB} MB)"
}

check_jar() {
  [ -f "$1" ] && [ -s "$1" ] || die "no such jar (or empty): $1"
  [ "$(head -c 2 "$1")" = "PK" ] || die "not a jar file: $1"
}

install_jar() { # jar slot
  local dest
  dest=$(jar_of "$2")
  run mkdir -p "$(dirname "$dest")"
  run install -m 0644 "$1" "$dest.new"
  run mv -f "$dest.new" "$dest" # atomic: a running JVM keeps its open file
  log "installed $(basename "$1") as $dest"
}

lock() {
  [ "$DRY_RUN" = 1 ] && return 0
  command -v flock >/dev/null 2>&1 || return 0
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "another web deploy is running" 3
}

# Waits until the slot reports ready. Fails early if its process exits.
wait_ready() {
  local slot=$1 deadline
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] wait until http://127.0.0.1:$(port_of "$slot")/actuator/health/readiness answers 200 (up to ${READY_TIMEOUT}s)"
    return 0
  fi
  deadline=$((SECONDS + READY_TIMEOUT))
  log "waiting for $(unit_of "$slot") to be ready on port $(port_of "$slot") (up to ${READY_TIMEOUT}s)"
  until ready "$slot"; do
    if ! active "$slot"; then
      journalctl -u "$(unit_of "$slot")" -n 40 --no-pager || true
      return 1
    fi
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep 2
  done
  log "$(unit_of "$slot") is ready"
}

status() {
  local s
  s=$(running_slot)
  echo "running: $s"
  [ "$s" = none ] && return 0
  if ready "$s"; then echo "ready: yes (port $(port_of "$s"))"; else echo "ready: no (port $(port_of "$s"))"; fi
}

# Start the idle slot (with a new jar, or the one it already has for --rollback), switch, stop the old one.
switch() { # jar-or-empty
  local jar=$1 old new
  set_up
  old=$(running_slot)
  case "$old" in
    legacy) new=b ;;     # the legacy unit uses port 8080, slot a's port
    none) new=a ;;
    *) new=$(other "$old") ;;
  esac
  no_worker_deploy
  if [ -n "$jar" ]; then
    check_jar "$jar"
  else
    if [ "$old" = legacy ] || [ "$old" = none ]; then
      die "nothing to roll back to: no earlier version is installed in a web slot" 3
    fi
    check_jar "$(jar_of "$new")"
    log "rolling back to the previous version in slot $new"
  fi
  if [ "$old" = none ]; then
    log "no web app is running; starting $(unit_of "$new")"
  else
    log "running: $(unit_of "$old") on port $(port_of "$old"); the new version goes to $(unit_of "$new") on port $(port_of "$new")"
    enough_memory
  fi

  if [ -n "$jar" ]; then
    install_jar "$jar" "$new"
  fi
  log "starting $(unit_of "$new")"
  run systemctl start "$(unit_of "$new")"
  if ! wait_ready "$new"; then
    run systemctl stop "$(unit_of "$new")" || true
    if [ "$old" = none ]; then
      die "the new version didn't get ready within ${READY_TIMEOUT}s and was stopped; no web app is running" 1
    fi
    die "the new version didn't get ready within ${READY_TIMEOUT}s and was stopped; $(unit_of "$old") was never touched and keeps serving" 1
  fi

  # Let Caddy's health check see the new slot before the old one starts refusing.
  if [ "$DRY_RUN" = 1 ]; then log "[dry-run] wait ${SETTLE_SECONDS}s for Caddy's health check"; else sleep "$SETTLE_SECONDS"; fi

  run systemctl enable --quiet "$(unit_of "$new")"
  if [ "$old" != none ]; then
    log "stopping $(unit_of "$old"): 5 s not ready, then it finishes the requests in flight"
    run systemctl stop "$(unit_of "$old")" || log "WARNING: stopping $(unit_of "$old") failed; check it"
    run systemctl disable --quiet "$(unit_of "$old")" || true
  fi

  if [ "$DRY_RUN" != 1 ] && ! ready "$new"; then
    journalctl -u "$(unit_of "$new")" -n 40 --no-pager || true
    die "$(unit_of "$new") stopped answering after the switch; run: $0 --rollback" 1
  fi
  log "done: $(unit_of "$new") serves on port $(port_of "$new")"
}

restart() { # jar
  local s
  check_jar "$1"
  s=$(running_slot)
  if [ "$s" = none ]; then
    if systemctl cat afklive-web@.service >/dev/null 2>&1 && [ -f "$ENV_DIR/web-slot-a.env" ]; then s=a; else s=legacy; fi
  fi
  install_jar "$1" "$s"
  log "restarting $(unit_of "$s"): the site answers with errors until it is back (about a minute)"
  run systemctl restart "$(unit_of "$s")"
  if [ "$s" != legacy ]; then run systemctl enable --quiet "$(unit_of "$s")"; fi
  wait_ready "$s" || die "$(unit_of "$s") didn't get ready within ${READY_TIMEOUT}s" 1
  log "done"
}

main() {
  local mode=switch jar=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run) DRY_RUN=1 ;;
      --restart) mode=restart ;;
      --rollback) mode=rollback ;;
      --status) mode=status ;;
      -h|--help) sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; exit 0 ;;
      -*) die "unknown option: $1 (see --help)" ;;
      *) jar=$1 ;;
    esac
    shift
  done
  if [ "$mode" != status ] && [ "$DRY_RUN" != 1 ] && [ "$(id -u)" -ne 0 ]; then
    die "run with sudo"
  fi
  case "$mode" in
    status) status ;;
    rollback) lock; switch "" ;;
    restart) [ -n "$jar" ] || die "usage: $0 --restart NEW_APP_JAR"; lock; restart "$jar" ;;
    *) [ -n "$jar" ] || die "usage: $0 [--dry-run] [--restart] NEW_APP_JAR | --rollback | --status"; lock; switch "$jar" ;;
  esac
}

main "$@"
