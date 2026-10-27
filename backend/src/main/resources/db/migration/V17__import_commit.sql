-- Committing imports (implementation-plan.md 5b): the job's progress, and each row's result.

ALTER TABLE import_jobs
    ADD COLUMN committed_by uuid REFERENCES users (id),
    ADD COLUMN queued_at    timestamptz,
    ADD COLUMN started_at   timestamptz,
    -- Moved on after each chunk; a RUNNING job whose heartbeat stops is resumed by the sweeper.
    ADD COLUMN heartbeat_at timestamptz,
    ADD COLUMN finished_at  timestamptz;

-- The sweeper's search, across businesses (system role).
CREATE INDEX ix_import_jobs_waiting ON import_jobs (status, queued_at) WHERE status IN ('QUEUED', 'RUNNING');

-- Set in the transaction that applied the row, so a resumed job skips exactly the rows already done.
ALTER TABLE import_rows
    ADD COLUMN result text,
    ADD CONSTRAINT ck_import_rows_result CHECK (result IN ('CREATED', 'UPDATED', 'UNCHANGED', 'FAILED'));
