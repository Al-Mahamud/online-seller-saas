#!/bin/bash
# One encrypted backup of the mojud database (implementation-plan.md 13b):
#   pg_dump (custom format) | age, to the public key AGE_RECIPIENT | BACKUP_REMOTE/mojud-<time>.dump.age
# BACKUP_REMOTE is an rclone remote and folder (r2:mojud-backups/db) or a plain folder. Only the
# public key is on the server, so the server cannot read its own backups. Then backups older than
# BACKUP_KEEP_DAYS are removed, and BACKUP_PING_URL (if set) is called to say tonight's worked.
set -euo pipefail
: "${AGE_RECIPIENT:?}" "${BACKUP_REMOTE:?}" "${PGHOST:?}" "${PGUSER:?}" "${PGPASSWORD:?}"
database="${PGDATABASE:-mojud}"
name="mojud-$(date -u +%Y%m%dT%H%M%SZ).dump.age"

echo "Backing up $database to $BACKUP_REMOTE/$name"
pg_dump --format=custom --dbname="$database" \
    | age --encrypt --recipient "$AGE_RECIPIENT" \
    | rclone rcat "$BACKUP_REMOTE/$name"

bytes=$(rclone size --json "$BACKUP_REMOTE/$name" | sed -n 's/.*"bytes":\([0-9]*\).*/\1/p')
if [ "${bytes:-0}" -lt 10000 ]; then
    echo "Backup $name is too small (${bytes:-0} bytes)" >&2
    exit 1
fi

rclone delete --min-age "${BACKUP_KEEP_DAYS:-30}d" --include "mojud-*.dump.age" "$BACKUP_REMOTE"
if [ -n "${BACKUP_PING_URL:-}" ]; then
    curl -fsS -m 10 --retry 3 "$BACKUP_PING_URL" > /dev/null
fi
echo "Backup $name done ($bytes bytes)"
