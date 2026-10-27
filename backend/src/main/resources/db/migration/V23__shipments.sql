-- Packing, shipping and delivery (implementation-plan.md 8a). Couriers are typed by staff in the
-- MVP (no booking API yet).

CREATE TABLE shipments (
    id             uuid        PRIMARY KEY,
    business_id    uuid        NOT NULL,
    order_id       uuid        NOT NULL,
    courier        text        NOT NULL,
    tracking_no    text        NOT NULL DEFAULT '',
    -- What the courier charges the business (poisha); not part of the order's total.
    courier_charge bigint      NOT NULL DEFAULT 0,
    -- IN_TRANSIT until DELIVERED, or RETURNED after a failed delivery (8c).
    status         text        NOT NULL,
    -- COD the courier is to collect (the order's cod_amount when shipped), and what it did (8b).
    cod_expected   bigint      NOT NULL,
    cod_collected  bigint,
    -- NONE (nothing to collect), WITH_COURIER, SETTLED (8b).
    cod_status     text        NOT NULL,
    shipped_by     uuid        REFERENCES users (id),
    shipped_at     timestamptz NOT NULL,
    delivered_at   timestamptz,
    settled_at     timestamptz,

    CONSTRAINT uq_shipments_business_id UNIQUE (business_id, id),
    CONSTRAINT fk_shipments_order FOREIGN KEY (business_id, order_id) REFERENCES orders (business_id, id),
    CONSTRAINT ck_shipments_courier CHECK (length(courier) BETWEEN 1 AND 50),
    CONSTRAINT ck_shipments_tracking CHECK (length(tracking_no) <= 100),
    CONSTRAINT ck_shipments_money CHECK (courier_charge >= 0 AND cod_expected >= 0 AND (cod_collected IS NULL OR cod_collected >= 0)),
    CONSTRAINT ck_shipments_status CHECK (status IN ('IN_TRANSIT', 'DELIVERED', 'RETURNED')),
    CONSTRAINT ck_shipments_cod_status CHECK (cod_status IN ('NONE', 'WITH_COURIER', 'SETTLED'))
);

-- One parcel on the road per order at a time (a re-shipment comes only after a failed one).
CREATE UNIQUE INDEX uq_shipments_in_transit ON shipments (order_id) WHERE status = 'IN_TRANSIT';
CREATE INDEX ix_shipments_order ON shipments (business_id, order_id, shipped_at);
CREATE INDEX ix_shipments_courier ON shipments (business_id, lower(courier));

SELECT mojud_enable_tenant_rls('shipments');
