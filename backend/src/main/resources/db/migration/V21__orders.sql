-- Orders: drafts, totals and numbering (implementation-plan.md 7a, backend-plan.md §5.3 and §6).

-- The next order number of each business. Taken with UPDATE ... RETURNING in the order's own
-- transaction: parallel orders queue on the row, and a rolled-back order gives its number back.
CREATE TABLE order_counters (
    business_id uuid   PRIMARY KEY REFERENCES businesses (id),
    next_number bigint NOT NULL,

    CONSTRAINT ck_order_counters_next CHECK (next_number >= 1)
);

SELECT mojud_enable_tenant_rls('order_counters');

CREATE TABLE orders (
    id                   uuid        PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES businesses (id),
    number               bigint      NOT NULL,
    -- As shown: "#1001", or with the business's prefix at the time, "RK-1001".
    number_label         text        NOT NULL,
    status               text        NOT NULL,
    source               text        NOT NULL,
    customer_id          uuid        NOT NULL,
    -- The customer as they were when ordering, for lists and search.
    customer_name        text        NOT NULL,
    customer_phone       text        NOT NULL,
    -- All money in poisha, computed by the server; the CHECKs below repeat the arithmetic.
    subtotal             bigint      NOT NULL,
    discount             bigint      NOT NULL,
    delivery_charge      bigint      NOT NULL,
    total                bigint      NOT NULL,
    advance_paid         bigint      NOT NULL,
    cod_amount           bigint      NOT NULL,
    -- Where it goes, copied onto the order: editing the customer later leaves old orders alone.
    delivery_name        text        NOT NULL,
    delivery_phone       text        NOT NULL,
    delivery_address     text        NOT NULL,
    delivery_district_id int         REFERENCES districts (id),
    delivery_area_id     int,
    note                 text        NOT NULL,
    created_by           uuid        REFERENCES users (id),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    confirmed_at         timestamptz,
    packed_at            timestamptz,
    shipped_at           timestamptz,
    delivered_at         timestamptz,
    cancelled_at         timestamptz,
    version              bigint      NOT NULL,

    CONSTRAINT uq_orders_business_id UNIQUE (business_id, id),
    CONSTRAINT uq_orders_number UNIQUE (business_id, number),
    CONSTRAINT fk_orders_customer FOREIGN KEY (business_id, customer_id) REFERENCES customers (business_id, id),
    CONSTRAINT fk_orders_area FOREIGN KEY (delivery_district_id, delivery_area_id) REFERENCES areas (district_id, id),
    CONSTRAINT ck_orders_area_needs_district CHECK (delivery_area_id IS NULL OR delivery_district_id IS NOT NULL),
    CONSTRAINT ck_orders_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED', 'RETURNED', 'CANCELLED')),
    CONSTRAINT ck_orders_source CHECK (source IN ('MANUAL', 'AI_DRAFT')),
    CONSTRAINT ck_orders_money CHECK (subtotal >= 0 AND discount >= 0 AND discount <= subtotal AND delivery_charge >= 0
        AND advance_paid >= 0 AND advance_paid <= total),
    CONSTRAINT ck_orders_total CHECK (total = subtotal - discount + delivery_charge),
    CONSTRAINT ck_orders_cod CHECK (cod_amount = total - advance_paid),
    CONSTRAINT ck_orders_phones CHECK (customer_phone ~ '^\+8801[3-9][0-9]{8}$' AND delivery_phone ~ '^\+8801[3-9][0-9]{8}$'),
    CONSTRAINT ck_orders_texts CHECK (length(delivery_name) BETWEEN 1 AND 100 AND length(delivery_address) <= 500
        AND length(note) <= 1000)
);

CREATE INDEX ix_orders_newest ON orders (business_id, created_at DESC, id DESC);
CREATE INDEX ix_orders_status ON orders (business_id, status, created_at DESC);
CREATE INDEX ix_orders_customer ON orders (business_id, customer_id, created_at DESC);
CREATE INDEX ix_orders_phones ON orders (business_id, customer_phone);
CREATE INDEX ix_orders_delivery_phone ON orders (business_id, delivery_phone);

SELECT mojud_enable_tenant_rls('orders');

-- What was ordered: name, price and cost copied at order time.
CREATE TABLE order_items (
    id            uuid   PRIMARY KEY,
    business_id   uuid   NOT NULL,
    order_id      uuid   NOT NULL,
    variant_id    uuid   NOT NULL,
    product_name  text   NOT NULL,
    variant_label text   NOT NULL,
    sku           text   NOT NULL,
    quantity      int    NOT NULL,
    unit_price    bigint NOT NULL,
    -- Null when the variant had no cost price.
    unit_cost     bigint,
    position      int    NOT NULL,

    CONSTRAINT fk_order_items_order FOREIGN KEY (business_id, order_id) REFERENCES orders (business_id, id),
    CONSTRAINT fk_order_items_variant FOREIGN KEY (business_id, variant_id) REFERENCES product_variants (business_id, id),
    CONSTRAINT uq_order_items_variant UNIQUE (order_id, variant_id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity BETWEEN 1 AND 10000),
    CONSTRAINT ck_order_items_money CHECK (unit_price >= 0 AND (unit_cost IS NULL OR unit_cost >= 0))
);

CREATE INDEX ix_order_items_order ON order_items (order_id, position);

SELECT mojud_enable_tenant_rls('order_items');

-- Every status change, never edited or deleted.
CREATE TABLE order_events (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL,
    order_id    uuid        NOT NULL,
    -- Null for the order's creation.
    from_status text,
    to_status   text        NOT NULL,
    user_id     uuid        REFERENCES users (id),
    note        text        NOT NULL,
    created_at  timestamptz NOT NULL,

    CONSTRAINT fk_order_events_order FOREIGN KEY (business_id, order_id) REFERENCES orders (business_id, id),
    CONSTRAINT ck_order_events_note CHECK (length(note) <= 1000)
);

CREATE INDEX ix_order_events_order ON order_events (order_id, created_at);

SELECT mojud_enable_tenant_rls('order_events');

REVOKE UPDATE, DELETE ON order_events FROM mojud_app, mojud_system;
