-- The stock ledger, the cached stock levels, and idempotency keys (implementation-plan.md 4a,
-- backend-plan.md §5.2 and §6).

-- One row per variant that has ever had stock. A cache of the ledger: on_hand and reserved always
-- equal the sums of stock_movements' deltas for the variant (checked nightly from 4b).
CREATE TABLE stock_levels (
    business_id uuid        NOT NULL,
    variant_id  uuid        PRIMARY KEY,
    on_hand     int         NOT NULL DEFAULT 0,
    -- Held for confirmed orders until they ship (stock_commit_point SHIP).
    reserved    int         NOT NULL DEFAULT 0,
    updated_at  timestamptz NOT NULL,

    CONSTRAINT fk_stock_levels_variant FOREIGN KEY (business_id, variant_id) REFERENCES product_variants (business_id, id),
    CONSTRAINT ck_stock_levels_on_hand CHECK (on_hand >= 0),
    CONSTRAINT ck_stock_levels_reserved CHECK (reserved >= 0 AND reserved <= on_hand)
);

SELECT mojud_enable_tenant_rls('stock_levels');

-- Every change to stock, never edited or deleted.
CREATE TABLE stock_movements (
    id             uuid        PRIMARY KEY,
    business_id    uuid        NOT NULL,
    variant_id     uuid        NOT NULL,
    type           text        NOT NULL,
    on_hand_delta  int         NOT NULL,
    reserved_delta int         NOT NULL,
    -- The level right after this movement, for reading the history without summing.
    on_hand_after  int         NOT NULL,
    reserved_after int         NOT NULL,
    -- What caused it: an adjustment batch, an order, a return or an import (later phases).
    reference_type text,
    reference_id   uuid,
    note           text,
    -- Who did it; null for the system (jobs).
    user_id        uuid        REFERENCES users (id),
    created_at     timestamptz NOT NULL,

    CONSTRAINT fk_stock_movements_variant FOREIGN KEY (business_id, variant_id) REFERENCES product_variants (business_id, id),
    CONSTRAINT ck_stock_movements_type CHECK (type IN ('OPENING', 'RECEIVE', 'ADJUST', 'DAMAGE', 'RESERVE', 'RELEASE',
        'SHIP', 'SALE', 'UNSALE', 'RETURN', 'RETURN_DAMAGED')),
    CONSTRAINT ck_stock_movements_reference CHECK (reference_type IN ('ADJUSTMENT', 'ORDER', 'RETURN', 'IMPORT')),
    CONSTRAINT ck_stock_movements_note CHECK (length(note) <= 500)
);

CREATE INDEX ix_stock_movements_variant ON stock_movements (business_id, variant_id, created_at DESC, id DESC);
CREATE INDEX ix_stock_movements_reference ON stock_movements (business_id, reference_type, reference_id);

SELECT mojud_enable_tenant_rls('stock_movements');

-- The ledger is append-only.
REVOKE UPDATE, DELETE ON stock_movements FROM mojud_app, mojud_system;

-- Requests sent with an Idempotency-Key header run once; a repeat gets the first answer.
CREATE TABLE idempotency_keys (
    business_id   uuid        NOT NULL,
    key           text        NOT NULL,
    request_hash  text        NOT NULL,
    response_body jsonb,
    created_at    timestamptz NOT NULL,

    PRIMARY KEY (business_id, key),
    CONSTRAINT ck_idempotency_keys_key CHECK (length(key) BETWEEN 1 AND 100)
);

SELECT mojud_enable_tenant_rls('idempotency_keys');
