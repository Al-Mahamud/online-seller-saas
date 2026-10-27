-- Returns and partial delivery (implementation-plan.md 8c).

CREATE TABLE returns (
    id               uuid        PRIMARY KEY,
    business_id      uuid        NOT NULL,
    order_id         uuid        NOT NULL,
    -- The shipment the goods came back from.
    shipment_id      uuid        NOT NULL,
    -- FAILED_DELIVERY (the parcel came back), PARTIAL_DELIVERY (items refused at the door),
    -- CUSTOMER_RETURN (sent back after delivery).
    kind             text        NOT NULL,
    reason           text        NOT NULL,
    -- What the customer no longer owes for the goods (poisha).
    credit           bigint      NOT NULL,
    -- Money given back to the customer with this return, if any.
    refund_amount    bigint      NOT NULL DEFAULT 0,
    refund_method    text,
    refund_reference text        NOT NULL DEFAULT '',
    created_by       uuid        REFERENCES users (id),
    created_at       timestamptz NOT NULL,

    CONSTRAINT uq_returns_business_id UNIQUE (business_id, id),
    CONSTRAINT fk_returns_order FOREIGN KEY (business_id, order_id) REFERENCES orders (business_id, id),
    CONSTRAINT fk_returns_shipment FOREIGN KEY (business_id, shipment_id) REFERENCES shipments (business_id, id),
    CONSTRAINT ck_returns_kind CHECK (kind IN ('FAILED_DELIVERY', 'PARTIAL_DELIVERY', 'CUSTOMER_RETURN')),
    CONSTRAINT ck_returns_reason CHECK (length(reason) BETWEEN 1 AND 500),
    CONSTRAINT ck_returns_money CHECK (credit >= 0 AND refund_amount >= 0),
    CONSTRAINT ck_returns_refund CHECK ((refund_amount = 0) = (refund_method IS NULL)),
    CONSTRAINT ck_returns_refund_method CHECK (refund_method IN ('BKASH', 'NAGAD', 'ROCKET', 'BANK', 'CASH', 'CARD', 'OTHER')),
    CONSTRAINT ck_returns_refund_reference CHECK (length(refund_reference) <= 100)
);

CREATE INDEX ix_returns_order ON returns (business_id, order_id, created_at);

SELECT mojud_enable_tenant_rls('returns');

-- Each returned item, and what became of it: back on the shelf, or written off as damaged.
CREATE TABLE return_items (
    business_id   uuid NOT NULL,
    return_id     uuid NOT NULL,
    order_item_id uuid NOT NULL,
    quantity      int  NOT NULL,
    disposition   text NOT NULL,

    PRIMARY KEY (return_id, order_item_id, disposition),
    CONSTRAINT fk_return_items_return FOREIGN KEY (business_id, return_id) REFERENCES returns (business_id, id),
    CONSTRAINT ck_return_items_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_return_items_disposition CHECK (disposition IN ('RESTOCK', 'WRITE_OFF'))
);

SELECT mojud_enable_tenant_rls('return_items');

-- What returns took off the order (the customer no longer owes it). What is owed is
-- total - return_credit - amount_paid; below zero, a refund is due.
ALTER TABLE orders
    ADD COLUMN return_credit bigint NOT NULL DEFAULT 0,
    ADD COLUMN returned_at   timestamptz,
    ADD CONSTRAINT ck_orders_return_credit CHECK (return_credit BETWEEN 0 AND total);

-- A refund after delivery can take amount_paid below the advance (the advance was spent on goods
-- that came back), so the floor is zero rather than the advance.
ALTER TABLE orders DROP CONSTRAINT ck_orders_amount_paid;
ALTER TABLE orders ADD CONSTRAINT ck_orders_amount_paid CHECK (amount_paid >= 0 AND amount_paid <= total);
