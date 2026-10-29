#!/bin/bash
# Restores a backup into a NEW database, never over the live one (implementation-plan.md 13b):
#   restore.sh <backup file name | latest> <age private key file> [new database, default mojud_restore]
# The roles must exist (they do on any server set up with compose.yml). Check the result, then
# switch to it with the app stopped (README.md, "Restoring").
set -euo pipefail
: "${BACKUP_REMOTE:?}" "${PGHOST:?}" "${PGUSER:?}" "${PGPASSWORD:?}"
name="${1:?backup file name, or latest}"
identity="${2:?age private key file}"
target="${3:-mojud_restore}"
if [[ ! "$target" =~ ^[a-z_][a-z0-9_]*$ ]]; then
    echo "Database name must be lower-case letters, digits and _" >&2
    exit 1
fi

if [ "$name" = latest ]; then
    name=$(rclone lsf --include "mojud-*.dump.age" "$BACKUP_REMOTE" | sort | tail -n 1)
    if [ -z "$name" ]; then
        echo "No backups in $BACKUP_REMOTE" >&2
        exit 1
    fi
fi
if [ -n "$(psql -d postgres -tAc "select 1 from pg_database where datname = '$target'")" ]; then
    echo "Database $target already exists: choose another name, or drop it first" >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
echo "Restoring $BACKUP_REMOTE/$name into $target"
rclone cat "$BACKUP_REMOTE/$name" | age --decrypt --identity "$identity" > "$work/mojud.dump"
createdb --owner=mojud_owner "$target"
pg_restore --exit-on-error --dbname="$target" "$work/mojud.dump"

psql -d "$target" -c "select version as schema_version, description from flyway_schema_history order by installed_rank desc limit 1"
psql -d "$target" -c "select (select count(*) from businesses) as businesses, (select count(*) from users) as users, (select count(*) from orders) as orders"
echo "Restored $name into $target"
