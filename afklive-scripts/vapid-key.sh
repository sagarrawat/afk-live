#!/bin/bash
# Prints a new VAPID key for push alerts (NT-1, MB-2), for APP_PUSH_VAPID_KEY.
# Keep it: changing it makes every device turn alerts on again.
set -euo pipefail
echo "APP_PUSH_VAPID_KEY=$(openssl ecparam -name prime256v1 -genkey -noout -outform DER | base64 -w0)"
