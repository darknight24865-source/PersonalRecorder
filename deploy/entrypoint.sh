#!/bin/sh
# Container entrypoint for the PersonalRecorder dashboard.
# Maps environment variables onto dashboard.py's CLI flags:
#   TOKEN  -> --token   (UI + command channel password; omit for no login)
#   CERT   -> --cert    (server.pem path, enables wss)
#   KEY    -> --key     (server.key path)
#   PORT   -> --port
#   DATA_DIR -> --out
set -eu

PORT="${PORT:-8765}"
DATA_DIR="${DATA_DIR:-/data}"

set -- python3 /srv/dashboard.py --host 0.0.0.0 --port "$PORT" --out "$DATA_DIR"
if [ -n "${TOKEN:-}" ]; then
    set -- "$@" --token "$TOKEN"
fi
if [ -n "${CERT:-}" ] && [ -n "${KEY:-}" ]; then
    set -- "$@" --cert "$CERT" --key "$KEY"
fi
exec "$@"