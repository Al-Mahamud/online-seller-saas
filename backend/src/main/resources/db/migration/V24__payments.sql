-- Payments and COD (implementation-plan.md 8b).

-- Money the customer paid the business directly (not through the courier): advances by bKash,
-- Nagad, bank, cash..., and later payments of a shortfall. COD the courier collected is on the
-- shipment (cod_collected), and its settlement to the business on the shipment too (settled_at).
CREATE TABLE payments (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL,
    order_id    uuid        NOT NULL,
    method      text        NOT NULL,
    amount      bigint      NOT NULL,
    reference   text        NOT NULL DEFAULT '',
    received_at timestamptz NOT NULL,
    recorded_by uuid        REFERENCES users (id),
    created_at  timestamptz NOT NULL,

    CONSTRAINT fk_payments_order FOREIGN KEY (business_id, order_id) REFERENCES orders (business_id, id),
    CONSTRAINT ck_payments_method CHECK (method IN ('BKASH', 'NAGAD', 'ROCKET', 'BANK', 'CASH', 'CARD', 'OTHER')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_reference CHECK (length(reference) <= 100)
);

CREATE INDEX ix_payments_order ON payments (business_id, order_id, received_at);

SELECT mojud_enable_tenant_rls('payments');

-- Everything the customer has paid: advances, then COD the courier collected, then any later
-- payment. payment_status (unpaid, partial, paid) is derived from it and the total.
-- No backfill: row-level security hides every order from this migration's role, and no orders
-- existed outside tests when this was added.
ALTER TABLE orders ADD COLUMN amount_paid bigint NOT NULL DEFAULT 0;
ALTER TABLE orders ADD CONSTRAINT ck_orders_amount_paid CHECK (amount_paid >= advance_paid AND amount_paid <= total);

CREATE INDEX ix_shipments_cod ON shipments (business_id, cod_status, lower(courier));
