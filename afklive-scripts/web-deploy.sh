#!/usr/bin/env bash
# OPS-4: deploys the AFK Live web app with no downtime (blue/green on one server).
#
# Two systemd instances of the web app, afklive@8080 and afklive@8082 (the worker keeps 8081), share
# one database; normally only one runs. Caddy proxies to both with health checks on
# /actuator/health/readiness and sends traffic to the first healthy one (docs/operations.md,
# "Zero-downtime deploys: web"). A deploy:
#   1. installs the new jar for the idle instance and starts it (the live one keeps serving);
#   2. waits until it reports ready (startup, Liquibase migrations included);
#   3. stops the old instance: it first reports "not ready" for a few seconds so Caddy moves traffic,
#      then finishes the requests in flight (graceful shutdown) and exits.
# If the new instance never gets ready it is stopped again and the old one keeps serving.
#
# Usage: web-deploy.sh [--dry-run] [--restart | --rollback] [JAR]
#   JAR          the new app.jar (default: $AFKLIVE_HOME/incoming/app.jar)
#   --dry-run    print what would change; changes nothing (still reads which instance is running)
#   --restart    old behaviour: replace the running instance's jar and restart it (about a minute of
#                502s). For emergencies, e.g. when two web apps don't fit in memory.
#   --rollback   no new jar: switch back to the other instance and the jar it last ran (after a
#                successful deploy, the previous version)
#
# Servers set up before blue/green (no afklive@.service yet) get the old restart of "afklive", so this
# script is safe to ship before the one-time server setup is done.
#
# Runs as the deploy user. Needs sudo (no password) for exactly the systemctl commands listed in
# docs/operations.md. Settings (environment): AFKLIVE_HOME (/opt/afklive), AFKLIVE_WEB_SLOTS
# ("8080 8082"), READY_TIMEOUT (seconds, 300), SETTLE_SECONDS (3: Caddy checks health every second).
set -euo pipefail

HOME_DIR="${AFKLIVE_HOME:-/opt/afklive}"
read -r -a SLOTS <<< "${AFKLIVE_WEB_SLOTS:-8080 8082}"
READY_TIMEOUT="${READY_TIMEOUT:-300}"
SETTLE_SECONDS="${SETTLE_SECONDS:-3}"
STATE_FILE="$HOME_DIR/web-slot"
LEGACY_UNIT="afklive"
LEGACY_PORT="${SLOTS[0]}"

DRY_RUN=0
MODE=bluegreen
JAR=""

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'
}

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# Runs a command that changes something, or only prints it in dry-run mode.
run() {
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] $*"
  else
    "$@"
  fi
}

# Privileged systemctl (sudoers allows exactly these forms; see docs/operations.md).
sysctl_priv() { run sudo -n systemctl "$@"; }

unit_of() { printf 'afklive@%s' "$1"; }
is_active() { systemctl is-active --quiet "$(unit_of "$1")"; }
has_template() { systemctl cat afklive@.service >/dev/null 2>&1; }

ready() {
  curl -fsS -m 3 -o /dev/null "http://127.0.0.1:$1/actuator/health/readiness" 2>/dev/null
}

# Waits until the instance on port $1 reports ready, up to READY_TIMEOUT seconds.
wait_ready() {
  local port="$1" waited=0
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] wait until http://127.0.0.1:$port/actuator/health/readiness answers 200 (up to ${READY_TIMEOUT}s)"
    return 0
  fi
  log "Waiting for the web app on port $port to be ready (up to ${READY_TIMEOUT}s)..."
  while [ "$waited" -lt "$READY_TIMEOUT" ]; do
    if ready "$port"; then
      log "Ready after ${waited}s"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  return 1
}

show_logs() {
  log "Last log lines of $1:"
  journalctl -u "$1" -n 60 --no-pager 2>/dev/null || log "(no access to the journal; add the deploy user to the systemd-journal group)"
}

other_slot() {
  local s
  for s in "${SLOTS[@]}"; do
    if [ "$s" != "$1" ]; then
      printf '%s' "$s"
      return
    fi
  done
}

valid_slot() {
  local s
  for s in "${SLOTS[@]}"; do
    [ "$s" = "$1" ] && return 0
  done
  return 1
}

check_jar() {
  [ -n "$JAR" ] || die "no jar given"
  if [ ! -f "$JAR" ] || [ ! -s "$JAR" ]; then
    die "jar not found or empty: $JAR"
  fi
  [ "$(head -c 2 "$JAR")" = "PK" ] || die "not a jar (zip) file: $JAR"
}

# Copies the jar next to its final name first, then renames it: a running JVM keeps the old file.
install_jar() {
  local target="$1"
  run install -m 0640 "$JAR" "$target.new"
  run mv -f "$target.new" "$target"
  log "Installed $(basename "$JAR") as $target"
}

write_state() {
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] record $1 as the live instance in $STATE_FILE"
  else
    printf '%s\n' "$1" > "$STATE_FILE"
  fi
}

lock() {
  [ "$DRY_RUN" = 1 ] && return 0
  exec 9>"$HOME_DIR/.web-deploy.lock"
  flock -n 9 || die "another web deploy is running"
}

deploy_legacy() {
  log "No afklive@.service on this server yet: restarting $LEGACY_UNIT in place (expect about a minute of downtime)"
  check_jar
  install_jar "$HOME_DIR/app.jar"
  sysctl_priv restart "$LEGACY_UNIT"
  if ! wait_ready "$LEGACY_PORT"; then
    show_logs "$LEGACY_UNIT"
    die "the web app didn't get ready within ${READY_TIMEOUT}s"
  fi
  log "Deployed (restart)"
}

# Picks the live instance and the idle one. Prints "<active> <idle>"; active is "-" when none runs.
pick_slots() {
  local running=() s recorded=""
  for s in "${SLOTS[@]}"; do
    if is_active "$s"; then
      running+=("$s")
    fi
  done
  if [ -f "$STATE_FILE" ]; then
    recorded="$(tr -d '[:space:]' < "$STATE_FILE")"
    valid_slot "$recorded" || recorded=""
  fi
  case "${#running[@]}" in
    0)
      local first="${recorded:-${SLOTS[0]}}"
      printf -- '- %s' "$first"
      ;;
    1)
      printf '%s %s' "${running[0]}" "$(other_slot "${running[0]}")"
      ;;
    *)
      # Both running (an interrupted deploy): keep the recorded one, replace the other.
      local keep="${recorded:-${SLOTS[0]}}"
      printf '%s %s' "$keep" "$(other_slot "$keep")"
      ;;
  esac
}

deploy_restart() {
  local active idle
  read -r active idle <<< "$(pick_slots)"
  [ "$active" = "-" ] && active="$idle"
  log "Restarting $(unit_of "$active") in place with the new jar (expect about a minute of downtime)"
  check_jar
  install_jar "$HOME_DIR/app-$active.jar"
  sysctl_priv restart "$(unit_of "$active")"
  sysctl_priv enable --quiet "$(unit_of "$active")"
  if ! wait_ready "$active"; then
    show_logs "$(unit_of "$active")"
    die "the web app didn't get ready within ${READY_TIMEOUT}s"
  fi
  write_state "$active"
  log "Deployed (restart) on port $active"
}

deploy_bluegreen() {
  local active idle rollback="$1"
  read -r active idle <<< "$(pick_slots)"
  if [ "$active" = "-" ]; then
    log "No web app is running; starting $(unit_of "$idle")"
  else
    log "Live: $(unit_of "$active"). New version goes to $(unit_of "$idle")"
  fi

  if [ "$rollback" = 1 ]; then
    [ -f "$HOME_DIR/app-$idle.jar" ] || die "nothing to roll back to: $HOME_DIR/app-$idle.jar is missing"
    log "Rolling back to the previous jar ($HOME_DIR/app-$idle.jar)"
  else
    check_jar
  fi

  if is_active "$idle"; then
    log "$(unit_of "$idle") is running too (interrupted deploy?); stopping it first"
    sysctl_priv stop "$(unit_of "$idle")"
  fi
  if [ "$rollback" != 1 ]; then
    install_jar "$HOME_DIR/app-$idle.jar"
  fi

  sysctl_priv start "$(unit_of "$idle")"
  if ! wait_ready "$idle"; then
    show_logs "$(unit_of "$idle")"
    sysctl_priv stop "$(unit_of "$idle")"
    if [ "$active" = "-" ]; then
      die "the new version didn't get ready within ${READY_TIMEOUT}s and was stopped; nothing is serving"
    fi
    die "the new version didn't get ready within ${READY_TIMEOUT}s and was stopped; $(unit_of "$active") keeps serving"
  fi

  # Let Caddy's health check see the new instance before the old one starts draining.
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] wait ${SETTLE_SECONDS}s for Caddy's health check"
  else
    sleep "$SETTLE_SECONDS"
  fi

  sysctl_priv enable --quiet "$(unit_of "$idle")"
  write_state "$idle"
  if [ "$active" != "-" ]; then
    sysctl_priv disable --quiet "$(unit_of "$active")"
    log "Stopping $(unit_of "$active") (drains, then finishes requests in flight)"
    sysctl_priv stop "$(unit_of "$active")"
  fi

  if [ "$DRY_RUN" != 1 ] && ! ready "$idle"; then
    show_logs "$(unit_of "$idle")"
    die "$(unit_of "$idle") stopped answering after the switch; run: $0 --rollback"
  fi
  log "Deployed: $(unit_of "$idle") is live"
}

main() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run) DRY_RUN=1 ;;
      --restart) MODE=restart ;;
      --rollback) MODE=rollback ;;
      -h|--help) usage; exit 0 ;;
      -*) die "unknown option: $1 (see --help)" ;;
      *) JAR="$1" ;;
    esac
    shift
  done
  [ -n "$JAR" ] || JAR="$HOME_DIR/incoming/app.jar"
  [ "${#SLOTS[@]}" -eq 2 ] || die "AFKLIVE_WEB_SLOTS must name two ports"

  lock
  if ! has_template; then
    [ "$MODE" = rollback ] && die "--rollback needs the blue/green setup (afklive@.service)"
    deploy_legacy
    return
  fi
  case "$MODE" in
    restart) deploy_restart ;;
    rollback) deploy_bluegreen 1 ;;
    *) deploy_bluegreen 0 ;;
  esac
}

main "$@"
