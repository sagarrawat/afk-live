#!/usr/bin/env bash
# Uploads a newly built app.jar to the server, sending only the parts that changed since the jar already there.
#
#   DO_HOST=... DO_USER=... DO_SSH_KEY="$(cat key)" upload-jar.sh LOCAL_JAR REMOTE_JAR
#
# Used by the deploy workflows (.github/workflows/web-build.yml, worker-deploy.yml) in place of copying the
# whole ~110 MB jar. The jar on the server from the previous deploy is the basis: rsync's delta transfer finds
# the blocks both files share (the ~100 MB of libraries in BOOT-INF/lib are stored uncompressed and don't change
# between builds) and sends only the rest: about 2 MB after a code change. With nothing on the server yet (first
# run) it sends the whole jar. The server runs exactly the same jar as before: nothing about how the app starts,
# the deploy slots or rollback changes.
#
# Safe next to a running JVM: rsync writes a temporary file in the same folder and renames it over REMOTE_JAR,
# so a process that has the old jar open keeps reading the old file (never --inplace). rsync checks the result
# against the local file's checksum; this script also compares SHA-256 on both sides.
#
# Exit 0: REMOTE_JAR is byte-for-byte LOCAL_JAR. Exit 5: rsync isn't installed on the server (one-time setup in
# docs/operations.md, "Uploads"); the workflow then copies the whole jar the old way. Any other failure: exit 1
# (the workflow also falls back to the whole-jar copy).
set -euo pipefail

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit "${2:-1}"; }

[ $# -eq 2 ] || die "usage: $0 LOCAL_JAR REMOTE_JAR (with DO_HOST, DO_USER and DO_SSH_KEY set)"
LOCAL=$1
REMOTE=$2
: "${DO_HOST:?DO_HOST is not set}"
: "${DO_USER:?DO_USER is not set}"
: "${DO_SSH_KEY:?DO_SSH_KEY is not set}"
SSH_PORT=${SSH_PORT:-22}

[ -f "$LOCAL" ] && [ -s "$LOCAL" ] || die "no such jar (or empty): $LOCAL"
[ "$(head -c 2 "$LOCAL")" = "PK" ] || die "not a jar file: $LOCAL"
[[ "$REMOTE" =~ ^/[A-Za-z0-9._/-]+$ ]] || die "REMOTE_JAR must be an absolute path of letters, digits, . _ - and /: $REMOTE"
command -v rsync >/dev/null 2>&1 || die "rsync is not installed here" 1

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
install -m 0600 /dev/null "$work/key"
printf '%s\n' "$DO_SSH_KEY" >"$work/key"
# Same trust as the scp/ssh actions the workflows used so far: the host key isn't pinned (see docs/operations.md).
ssh_opts=(-i "$work/key" -p "$SSH_PORT" -o BatchMode=yes -o IdentitiesOnly=yes -o ConnectTimeout=20
  -o ServerAliveInterval=15 -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$work/known_hosts" -o LogLevel=ERROR)
# shellcheck disable=SC2029 # the remote command is built here on purpose (REMOTE_JAR was checked above)
remote() { ssh "${ssh_opts[@]}" "$DO_USER@$DO_HOST" "$@"; }

dir=$(dirname "$REMOTE")
# One round trip: is rsync there, does the folder exist, and what is there now.
probe=$(remote "command -v rsync >/dev/null 2>&1 || { echo no-rsync; exit 0; }; [ -d '$dir' ] || { echo no-dir; exit 0; }; if [ -f '$REMOTE' ]; then echo \"basis \$(stat -c %s '$REMOTE')\"; else echo first; fi") \
  || die "can't reach $DO_HOST over ssh"
case "$probe" in
  no-rsync) die "rsync isn't installed on the server (sudo apt-get install -y rsync, see docs/operations.md)" 5 ;;
  no-dir) die "$dir doesn't exist on the server (one-time setup in docs/operations.md)" ;;
  first) log "nothing at $REMOTE yet: sending the whole jar this once" ;;
  basis*) log "sending what changed relative to the jar already at $REMOTE (${probe#basis } bytes)" ;;
  *) die "unexpected answer from the server: $probe" ;;
esac

size=$(stat -c %s "$LOCAL")
start=$SECONDS
# --no-whole-file: always use the delta algorithm. -z: compress what is sent. --chmod: the same mode scp gave.
# No --inplace: the new jar is renamed into place, so a running JVM keeps its open file.
rsync --no-whole-file -z --chmod=F644 --stats -e "ssh ${ssh_opts[*]}" "$LOCAL" "$DO_USER@$DO_HOST:$REMOTE" >"$work/stats" \
  || { cat "$work/stats" >&2; die "rsync failed"; }
sent=$(sed -n 's/^Total bytes sent: \([0-9,]*\).*/\1/p' "$work/stats" | tr -d ,)
received=$(sed -n 's/^Total bytes received: \([0-9,]*\).*/\1/p' "$work/stats" | tr -d ,)

want=$(sha256sum "$LOCAL" | cut -d' ' -f1)
got=$(remote "sha256sum '$REMOTE'" | cut -d' ' -f1) || die "can't read back $REMOTE"
[ "$want" = "$got" ] || die "the jar on the server doesn't match the one built here (sha256 $got, expected $want)"

log "uploaded $size bytes as $REMOTE: sent ${sent:-?} bytes, received ${received:-?} bytes, in $((SECONDS - start)) s (sha256 $want)"
