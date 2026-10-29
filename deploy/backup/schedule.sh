#!/bin/bash
# Runs backup.sh once a day at BACKUP_AT (UTC; 20:30 is 02:30 in Dhaka). A failed night is logged
# and the next one still runs; BACKUP_PING_URL (a dead-man's switch) notices missing nights.
set -u
at="${BACKUP_AT:-20:30}"
trap 'exit 0' TERM INT
echo "Backups daily at $at UTC to ${BACKUP_REMOTE:?}"
while true; do
    if [ "$(date -u +%H:%M)" = "$at" ]; then
        backup.sh || echo "Backup failed" >&2
        sleep 60 & wait $!
    fi
    sleep 20 & wait $!
done
