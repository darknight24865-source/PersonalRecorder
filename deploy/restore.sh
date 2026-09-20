#!/bin/bash
# PersonalRecorder dashboard — restore the data dir from a backup made by
# deploy/backup.sh.
#
# Usage:
#   deploy/restore.sh /var/backups/pr/pr-dashboard-20260131_031500.tar.gz [data_dir]
#
# Stops the service first, replaces the data dir, then starts it again.
# (If the backup also contained /etc/personalrecorder/certs, those are NOT
# touched — reinstall them manually to avoid surprises on a fresh box.)
set -euo pipefail

BACKUP="${1:?usage: restore.sh <backup.tar.gz> [data_dir]}"
DATA_DIR="${2:-/var/lib/personalrecorder}"
SERVICE="${SERVICE:-personalrecorder-dashboard}"

if [ ! -f "$BACKUP" ]; then
    echo "error: backup file $BACKUP not found" >&2
    exit 1
fi

if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
    echo "stopping $SERVICE..."
    systemctl stop "$SERVICE"
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "extracting $BACKUP ..."
tar -xzf "$BACKUP" -C "$STAGE"

# Locate the data-dir subtree by its characteristic files, so an optional
# etc/personalrecorder/certs entry in the archive cannot be mistaken for it.
DATA_SUB="$(find "$STAGE" -mindepth 2 -maxdepth 3 \( -name db.json -o -name devices.json \) -print -quit)"
if [ -z "$DATA_SUB" ]; then
    echo "error: archive contains no dashboard data dir (no db.json/devices.json)" >&2
    exit 1
fi
TOPDIR="$(dirname "$DATA_SUB")"

mkdir -p "$DATA_DIR"
rm -rf "$DATA_DIR"/*
cp -a "$TOPDIR"/. "$DATA_DIR"/
chown -R --reference="$DATA_DIR" "$DATA_DIR" 2>/dev/null || true

if systemctl list-unit-files "$SERVICE" >/dev/null 2>&1; then
    echo "starting $SERVICE..."
    systemctl start "$SERVICE"
fi

echo "restore complete: $DATA_DIR"