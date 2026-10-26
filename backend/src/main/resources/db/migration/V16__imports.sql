-- Product imports from .xlsx and .csv (implementation-plan.md 5a). The file is read when uploaded
-- and only its non-blank rows are kept, as text; the file itself is not stored.

CREATE TABLE import_jobs (
    id              uuid        PRIMARY KEY,
    business_id     uuid        NOT NULL REFERENCES businesses (id),
    file_name       text        NOT NULL,
    file_type       text        NOT NULL,
    -- MAPPING while columns are chosen; QUEUED, RUNNING, DONE or FAILED once committed (5b).
    status          text        NOT NULL,
    -- The sheet row holding the column headers; rows below it are data.
    header_row      int         NOT NULL,
    column_count    int         NOT NULL,
    -- [{"index": 0, "field": "NAME", "optionName": null}, ...]
    mapping         jsonb       NOT NULL,
    -- Totals of the latest check of the data rows.
    total_rows      int         NOT NULL DEFAULT 0,
    valid_rows      int         NOT NULL DEFAULT 0,
    create_rows     int         NOT NULL DEFAULT 0,
    update_rows     int         NOT NULL DEFAULT 0,
    -- Categories the sheet names that do not exist yet; created on commit.
    new_categories  jsonb       NOT NULL DEFAULT '[]',
    created_by      uuid        NOT NULL REFERENCES users (id),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,

    CONSTRAINT uq_import_jobs_business_id UNIQUE (business_id, id),
    CONSTRAINT ck_import_jobs_file_name CHECK (length(file_name) BETWEEN 1 AND 255),
    CONSTRAINT ck_import_jobs_file_type CHECK (file_type IN ('CSV', 'XLSX')),
    CONSTRAINT ck_import_jobs_status CHECK (status IN ('MAPPING', 'QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_import_jobs_columns CHECK (column_count BETWEEN 1 AND 100)
);

CREATE INDEX ix_import_jobs_newest ON import_jobs (business_id, created_at DESC);

SELECT mojud_enable_tenant_rls('import_jobs');

CREATE TABLE import_rows (
    business_id uuid  NOT NULL,
    job_id      uuid  NOT NULL,
    -- As numbered in the sheet (1 is the first row), so errors point at the row the seller sees.
    row_number  int   NOT NULL,
    -- Cell text by column, ["Cotton Kurti", "KURTI-S", "৳1,200", ...]
    cells       jsonb NOT NULL,
    -- From the latest check: CREATE or UPDATE when valid, null when not (or above the headers).
    action      text,
    -- [{"column": 2, "field": "PRICE", "code": "not_a_price", "args": []}, ...]
    errors      jsonb NOT NULL DEFAULT '[]',

    PRIMARY KEY (job_id, row_number),
    CONSTRAINT fk_import_rows_job FOREIGN KEY (business_id, job_id)
        REFERENCES import_jobs (business_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_import_rows_action CHECK (action IN ('CREATE', 'UPDATE')),
    CONSTRAINT ck_import_rows_row_number CHECK (row_number >= 1)
);

SELECT mojud_enable_tenant_rls('import_rows');
