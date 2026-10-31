#!/usr/bin/env bash
# OPS-7: keep integration secrets in GitHub (repository secrets) instead of editing the server by hand.
#
#   printf 'GOOGLE_CLIENT_ID=%s\n' "$GOOGLE_CLIENT_ID" ... | sudo sync-secrets.sh
#
# The web deploy workflow pipes KEY=VALUE lines on stdin (never as arguments, so they don't show in `ps`) and
# this script writes them into /opt/afklive/afklive.env, the file every AFK Live service reads at start:
#   - only keys on the ALLOWED list below; anything else is refused and reported by name;
#   - an empty value is skipped, so a secret not set in GitHub never wipes the server's value;
#   - a key already present is replaced in place, a new key is appended; every other line is kept as it is;
#   - values are written single-quoted (systemd and bash both read that); a value with a single quote or a
#     line break is refused, because neither format can hold it safely;
#   - the file is rewritten atomically with its owner and mode kept, after a copy to afklive.env.bak (mode 600).
# Values are never printed: the output names the keys that changed. Services pick the new values up when they
# next start: the web app on this deploy, a worker only on its own next deploy.
#
# Exit 0: done (even when nothing changed). Exit 2: bad input (nothing written). Exit 4: no env file.
# One-time server setup (install + sudoers line): docs/operations.md ("Secrets from GitHub").
set -euo pipefail

ALLOWED=(
  GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GOOGLE_YOUTUBE_CLIENT_ID GOOGLE_YOUTUBE_CLIENT_SECRET
  ANTHROPIC_API_KEY
  APP_PUSH_VAPID_KEY
  APP_TURNSTILE_SITE_KEY APP_TURNSTILE_SECRET
  PHONEPE_CLIENT_ID PHONEPE_CLIENT_SECRET PHONEPE_CLIENT_VERSION PHONEPE_CALLBACK_PASSWORD
  ZEPTOMAIL_TOKEN
  STORAGE_ACCESS_KEY STORAGE_SECRET_KEY
)
# Deliberately not here: server-generated or data-critical values (APP_ENCRYPTION_KEY, POSTGRES_PASSWORD,
# REMEMBER_ME_KEY, WORKER_CONTROL_TOKEN, INGEST_*). A wrong copy of those breaks sign-in or makes stored
# stream keys unreadable, so they stay on the server only. GEMINI_API_KEY and SMTP_* are left out for now:
# GitHub holds older copies (Jan 2026) that could overwrite working values on the server. Add them back once
# the GitHub copies are confirmed current.

ENV_FILE=/opt/afklive/afklive.env
# Tests point it elsewhere. Under sudo the path is fixed (sudo also drops the variable), so the sudoers line
# can't be used to write any other file.
if [ -z "${SUDO_USER:-}" ] && [ -n "${SYNC_SECRETS_ENV_FILE:-}" ]; then
  ENV_FILE=$SYNC_SECRETS_ENV_FILE
fi

[ -f "$ENV_FILE" ] || { echo "No $ENV_FILE on this server; nothing written." >&2; exit 4; }

allowed() {
  local k
  for k in "${ALLOWED[@]}"; do [ "$k" = "$1" ] && return 0; done
  return 1
}

declare -A NEW=()
bad=0
while IFS= read -r line || [ -n "$line" ]; do
  [ -z "$line" ] && continue
  key=${line%%=*}
  value=${line#*=}
  if [ "$key" = "$line" ] || ! [[ $key =~ ^[A-Z][A-Z0-9_]*$ ]]; then
    echo "Ignored a line that isn't KEY=VALUE." >&2; bad=1; continue
  fi
  if ! allowed "$key"; then
    echo "Refused $key: not on the list of secrets GitHub may set (sync-secrets.sh)." >&2; bad=1; continue
  fi
  [ -z "$value" ] && continue
  if [[ $value == *"'"* ]]; then
    echo "Refused $key: its value has a single quote, which the env file can't hold safely." >&2; bad=1; continue
  fi
  NEW[$key]=$value
done

if [ "$bad" = 1 ]; then
  echo "Nothing written." >&2
  exit 2
fi
if [ "${#NEW[@]}" = 0 ]; then
  echo "No secrets to sync."
  exit 0
fi

dir=$(dirname "$ENV_FILE")
tmp=$(mktemp "$dir/.afklive.env.XXXXXX")
trap 'rm -f "$tmp"' EXIT
chmod --reference="$ENV_FILE" "$tmp"
chown --reference="$ENV_FILE" "$tmp" 2>/dev/null || true

declare -A SEEN=()
changed=()
while IFS= read -r line || [ -n "$line" ]; do
  key=${line%%=*}
  if [ "$key" != "$line" ] && [ -n "${NEW[$key]+x}" ]; then
    if [ -n "${SEEN[$key]+x}" ]; then
      continue                                   # a duplicate line of a key we set: drop it
    fi
    SEEN[$key]=1
    want="$key='${NEW[$key]}'"
    [ "$line" != "$want" ] && changed+=("$key")
    printf '%s\n' "$want" >> "$tmp"
  else
    printf '%s\n' "$line" >> "$tmp"
  fi
done < "$ENV_FILE"
for key in "${!NEW[@]}"; do
  if [ -z "${SEEN[$key]+x}" ]; then
    printf '%s\n' "$key='${NEW[$key]}'" >> "$tmp"
    changed+=("$key")
  fi
done

if [ "${#changed[@]}" = 0 ]; then
  echo "Secrets already up to date."
  exit 0
fi

install -m 600 /dev/null "$ENV_FILE.bak"
cat "$ENV_FILE" > "$ENV_FILE.bak"
mv -f "$tmp" "$ENV_FILE"
trap - EXIT
IFS=$'\n' sorted=($(printf '%s\n' "${changed[@]}" | sort)); unset IFS
echo "Updated: ${sorted[*]}"
