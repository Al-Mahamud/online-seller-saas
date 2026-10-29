-- The three database roles (backend-plan.md §4). Run once per PostgreSQL server, as a superuser,
-- before the first migration; then create the database owned by mojud_owner:
--   CREATE DATABASE mojud OWNER mojud_owner;
-- Placeholders in ${...} are replaced by the caller: the test setup does it in Java, and Phase 13b
-- adds a deploy script. Each statement ends with ";" at the end of a line.

-- Owns the schema. Only Flyway connects as this role.
CREATE ROLE mojud_owner LOGIN PASSWORD '${owner_password}';

-- Serves every API request. Row-level security applies to it; it cannot change the schema.
CREATE ROLE mojud_app LOGIN PASSWORD '${app_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- Jobs that scan every business, payment-gateway callbacks and platform admin. Bypasses
-- row-level security, so only code in "system" packages may use it (SystemDatabaseAccessTests).
CREATE ROLE mojud_system LOGIN PASSWORD '${system_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;
