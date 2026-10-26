#!/usr/bin/env bash
# FO-8: deploy a worker jar to the additional worker hosts (remote pools), one host at a time, each with the
# same zero-interruption handover as the main server (worker-handover-deploy.sh on that host).
#
#   deploy-worker-hosts.sh [--restart] [--dry-run] APP_JAR
#
# Called by .github/workflows/worker-deploy.yml when the run asks for extra hosts; it never runs otherwise.
# Settings (GitHub secrets, passed as environment variables):
#   WORKER_HOSTS        hosts, separated by spaces, commas or new lines: [user@]host[:port] (user: WORKER_HOSTS_USER)
#   WORKER_HOSTS_USER   default login, "deploy" if empty
#   SSH_KEY             private key the hosts accept for that user
#   KNOWN_HOSTS         their host keys (ssh-keyscan -H host, checked by hand): connections to a host whose key
#                       isn't listed are refused, so a hijacked address never receives the jar
#
# Hosts go in order; the first one that fails stops the run (exit 1), so later hosts keep the old version and
# streams stay where they are. Each host's script decides what is safe exactly as on the main server.
set -euo pipefail

INCOMING=/opt/afklive/worker/incoming/app.jar
SCRIPT=/opt/afklive/scripts/worker-handover-deploy.sh
DRY_RUN=0
RESTART=0
JAR=""

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $1"; exit "${2:-1}"; }

for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=1 ;;
    --restart) RESTART=1 ;;
    -*) die "usage: $0 [--restart] [--dry-run] APP_JAR" 2 ;;
    *) JAR=$a ;;
  esac
done
[ -n "$JAR" ] && [ -f "$JAR" ] || die "usage: $0 [--restart] [--dry-run] APP_JAR (no such jar: ${JAR:-none})" 2

read -r -a HOSTS <<<"$(printf '%s' "${WORKER_HOSTS:-}" | tr ',\n' '  ')"
[ "${#HOSTS[@]}" -gt 0 ] || die "WORKER_HOSTS is empty: add the extra worker hosts as a repository secret (docs/operations.md, \"Worker pools\")" 2
if [ "$DRY_RUN" != 1 ]; then
  [ -n "${SSH_KEY:-}" ] || die "SSH_KEY is empty" 2
  [ -n "${KNOWN_HOSTS:-}" ] || die "KNOWN_HOSTS is empty: pin the hosts' keys (ssh-keyscan -H HOST, verified)" 2
fi
USER_DEFAULT=${WORKER_HOSTS_USER:-deploy}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
(umask 077; printf '%s\n' "${SSH_KEY:-}" > "$WORK/key"; printf '%s\n' "${KNOWN_HOSTS:-}" > "$WORK/known_hosts")

if [ "$RESTART" = 1 ]; then
  REMOTE="sudo $SCRIPT --restart $INCOMING"
else
  REMOTE="if [ ! -x $SCRIPT ]; then echo 'The handover is not set up on this host (provision-remote-worker.sh)'; exit 4; fi; sudo $SCRIPT $INCOMING"
fi

n=0
for h in "${HOSTS[@]}"; do
  n=$((n + 1))
  target=$h port=22
  [[ "$target" == *@* ]] || target="$USER_DEFAULT@$target"
  if [[ "$target" =~ ^(.+):([0-9]+)$ ]]; then target=${BASH_REMATCH[1]}; port=${BASH_REMATCH[2]}; fi
  opts=(-i "$WORK/key" -o "UserKnownHostsFile=$WORK/known_hosts" -o StrictHostKeyChecking=yes -o BatchMode=yes -o ConnectTimeout=20)
  log "[$n/${#HOSTS[@]}] $target: $([ "$RESTART" = 1 ] && echo restart || echo handover)"
  if [ "$DRY_RUN" = 1 ]; then
    log "[dry-run] scp -P $port $JAR $target:$INCOMING"
    log "[dry-run] ssh -p $port $target $REMOTE"
    continue
  fi
  if ! scp -q -P "$port" "${opts[@]}" "$JAR" "$target:$INCOMING"; then
    die "couldn't copy the jar to $target; it and the hosts after it keep the old version" 1
  fi
  if ! ssh -p "$port" "${opts[@]}" "$target" "$REMOTE"; then
    die "the deploy on $target failed (its output is above); the hosts after it keep the old version" 1
  fi
  log "[$n/${#HOSTS[@]}] $target: done"
done
log "all ${#HOSTS[@]} extra hosts deployed"
