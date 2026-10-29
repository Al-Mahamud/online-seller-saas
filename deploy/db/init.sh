#!/bin/bash
# First start of an empty database volume only (PostgreSQL's init folder): the three Mojud roles
# from roles.sql (the same file the tests use) and the mojud database owned by mojud_owner.
set -euo pipefail

for name in DB_OWNER_PASSWORD DB_APP_PASSWORD DB_SYSTEM_PASSWORD; do
    if [[ ! "${!name:-}" =~ ^[A-Za-z0-9]{24,}$ ]]; then
        echo "$name must be at least 24 letters and digits (openssl rand -hex 24)" >&2
        exit 1
    fi
done

sed -e "s/\${owner_password}/${DB_OWNER_PASSWORD}/" \
    -e "s/\${app_password}/${DB_APP_PASSWORD}/" \
    -e "s/\${system_password}/${DB_SYSTEM_PASSWORD}/" /mojud/roles.sql \
    | psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "CREATE DATABASE mojud OWNER mojud_owner"
