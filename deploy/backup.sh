#!/bin/bash
# PersonalRecorder dashboard — backup the data dir.
#
# Stores everything the dashboard owns: devices.json (paired phones),
# db.json (media index), locations.jsonl and media/ (screenshots, video,
# audio). Also includes the TLS cert pair if present.
#
# Usage:
#   deploy/backup.sh [data_dir] [dest_dir]
#   KEEP=14 deploy/backup.sh /var/lib/personalrecorder /var/backups/pr
#
# Add a cron line for nightly backups (adjust paths for your install):
#   15 3 * * * /opt/personalrecorder/deploy/backup.sh /var/lib/personalrecorder /var/backups/pr
set -euo pipefail

DATA_DIR="${1:-/var/lib/personalrecorder}"
DEST="${2:-/var/backups/personalrecorder}"
KEEP="${KEEP:-7}"

if [ ! -d "$DATA_DIR" ]; then
    echo "error: data dir $DATA_DIR not found" >&2
    exit 1
fi

mkdir -p "$DEST"
STAMP="$(date +%Y%m%d_%H%M%S)"
OUT="$DEST/pr-dashboard-$STAMP.tar"

# tar from the parent so the archive re-extracts as <name>/..., then
# optionally add the cert pair and compress.
tar -cf "$OUT" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"
if [ -f /etc/personalrecorder/certs/server.pem ] && [ -f /etc/personalrecorder/certs/server.key ]; then
    tar -rf "$OUT" -C / etc/personalrecorder/certs
fi
gzip -f "$OUT"
OUTGZ="$OUT.gz"

echo "backup written: $OUTGZ ($(du -h "$OUTGZ" | cut -f1))"

# Prune old backups, keep the newest $KEEP.
ls -1t "$DEST"/pr-dashboard-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
    rm -f -- "$old"
    echo "pruned: $old"
done