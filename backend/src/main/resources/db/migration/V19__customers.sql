-- A business's customers (implementation-plan.md 6a), one per phone number.

CREATE TABLE customers (
    id            uuid        PRIMARY KEY,
    business_id   uuid        NOT NULL REFERENCES businesses (id),
    name          text        NOT NULL,
    -- E.164 (+8801712345678), so every way of writing a number finds the same customer.
    phone         text        NOT NULL,
    alt_phone     text,
    address       text        NOT NULL DEFAULT '',
    district_id   int         REFERENCES districts (id),
    area_id       int,
    note          text        NOT NULL DEFAULT '',
    -- Kept up to date by orders (Phase 7).
    order_count   int         NOT NULL DEFAULT 0,
    last_order_at timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL,

    CONSTRAINT uq_customers_business_id UNIQUE (business_id, id),
    CONSTRAINT uq_customers_phone UNIQUE (business_id, phone),
    -- The area is in the district (checked only when both are given).
    CONSTRAINT fk_customers_area FOREIGN KEY (district_id, area_id) REFERENCES areas (district_id, id),
    CONSTRAINT ck_customers_area_needs_district CHECK (area_id IS NULL OR district_id IS NOT NULL),
    CONSTRAINT ck_customers_name CHECK (length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_customers_phone CHECK (phone ~ '^\+8801[3-9][0-9]{8}$'),
    CONSTRAINT ck_customers_alt_phone CHECK (alt_phone ~ '^\+8801[3-9][0-9]{8}$'),
    CONSTRAINT ck_customers_address CHECK (length(address) <= 500),
    CONSTRAINT ck_customers_note CHECK (length(note) <= 1000),
    CONSTRAINT ck_customers_order_count CHECK (order_count >= 0)
);

CREATE INDEX ix_customers_newest ON customers (business_id, created_at DESC, id DESC);
-- Search: part of a name (any case, small typos) or part of a phone number.
CREATE INDEX ix_customers_name_trgm ON customers USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_customers_phone_trgm ON customers USING gin (phone gin_trgm_ops);

SELECT mojud_enable_tenant_rls('customers');
