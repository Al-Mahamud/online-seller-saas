-- Businesses, their settings and memberships (implementation-plan.md 2a, backend-plan.md §5.1).

-- One row per seller. The business's own id is its tenant key, so its row-level security policy
-- compares id rather than business_id.
CREATE TABLE businesses (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    phone      text,
    address    text,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL,

    CONSTRAINT ck_businesses_name CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_businesses_phone CHECK (phone ~ '^\+8801[3-9][0-9]{8}$'),
    CONSTRAINT ck_businesses_address CHECK (length(address) <= 300),
    -- SUSPENDED arrives with platform admin (Phase 12a).
    CONSTRAINT ck_businesses_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

ALTER TABLE businesses ENABLE ROW LEVEL SECURITY;
ALTER TABLE businesses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON businesses
    USING (id = mojud_current_business())
    WITH CHECK (id = mojud_current_business());

-- Settings a business changes itself. Order numbering is owned by the orders module (Phase 7a),
-- not stored here, so no other module writes this table.
CREATE TABLE business_settings (
    business_id        uuid        PRIMARY KEY REFERENCES businesses (id),
    -- SHIP: reserve at confirm, deduct at hand-over to the courier. CONFIRM: deduct at confirm.
    stock_commit_point text        NOT NULL DEFAULT 'SHIP',
    -- Threshold given to new variants (each variant can override it).
    low_stock_default  int         NOT NULL DEFAULT 5,
    order_number_prefix text       NOT NULL DEFAULT '',
    invoice_footer     text        NOT NULL DEFAULT '',
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL,

    CONSTRAINT ck_business_settings_commit CHECK (stock_commit_point IN ('SHIP', 'CONFIRM')),
    CONSTRAINT ck_business_settings_low_stock CHECK (low_stock_default BETWEEN 0 AND 100000),
    CONSTRAINT ck_business_settings_prefix CHECK (order_number_prefix ~ '^[A-Z0-9-]{0,10}$'),
    CONSTRAINT ck_business_settings_footer CHECK (length(invoice_footer) <= 500)
);

SELECT mojud_enable_tenant_rls('business_settings');

-- Who works for which business, and in which role.
CREATE TABLE memberships (
    id           uuid        PRIMARY KEY,
    business_id  uuid        NOT NULL REFERENCES businesses (id),
    user_id      uuid        NOT NULL REFERENCES users (id),
    role         text        NOT NULL,
    status       text        NOT NULL,
    -- When the user last switched to this business; picks the business selected after sign-in.
    last_used_at timestamptz NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL,

    CONSTRAINT uq_memberships_user UNIQUE (business_id, user_id),
    CONSTRAINT ck_memberships_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF')),
    CONSTRAINT ck_memberships_status CHECK (status IN ('ACTIVE', 'REMOVED'))
);

-- Exactly one active owner per business (ownership transfer is Phase 2b).
CREATE UNIQUE INDEX uq_memberships_owner ON memberships (business_id)
    WHERE role = 'OWNER' AND status = 'ACTIVE';

-- A user's businesses, looked up by the system role at sign-in and for the business switcher.
CREATE INDEX ix_memberships_user ON memberships (user_id, last_used_at DESC);

SELECT mojud_enable_tenant_rls('memberships');
