-- Runs as mojud_owner, which owns the database and so the public schema. The roles themselves are
-- created outside Flyway (db/bootstrap/roles.sql) because creating roles needs a superuser.

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO mojud_app, mojud_system;

-- Every table and sequence created by later migrations: read and write data, never change the
-- schema. Append-only tables (the stock ledger, audit log) revoke UPDATE and DELETE afterwards.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mojud_app, mojud_system;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO mojud_app, mojud_system;
