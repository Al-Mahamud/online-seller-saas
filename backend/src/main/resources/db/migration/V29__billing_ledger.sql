-- Upgrades, bank transfers and the billing ledger (implementation-plan.md 11b).

-- How an invoice is to be paid: through the gateway, or by bank transfer marked paid by an admin.
ALTER TABLE invoices ADD COLUMN pay_by text NOT NULL DEFAULT 'SSLCOMMERZ';
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_pay_by CHECK (pay_by IN ('SSLCOMMERZ', 'BANK_TRANSFER'));
-- An upgrade buys the rest of a running period: no months of its own.
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_months;
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_months CHECK (
    (kind = 'SUBSCRIPTION' AND months IN (1, 6, 12)) OR (kind <> 'SUBSCRIPTION' AND months = 0));

-- Money received for invoices (and later refunds and adjustments), for the accountant. Written in
-- the transaction that marks an invoice paid; never changed.
CREATE TABLE billing_ledger (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL,
    invoice_id  uuid        NOT NULL,
    type        text        NOT NULL,
    -- Poisha; a refund is negative.
    amount      bigint      NOT NULL,
    vat_amount  bigint      NOT NULL,
    method      text        NOT NULL,
    -- The gateway's tran_id and val_id, or the bank transfer's reference.
    reference   text        NOT NULL,
    recorded_by uuid        REFERENCES users (id),
    created_at  timestamptz NOT NULL,

    CONSTRAINT fk_billing_ledger_invoice FOREIGN KEY (business_id, invoice_id) REFERENCES invoices (business_id, id),
    CONSTRAINT ck_billing_ledger_type CHECK (type IN ('PAYMENT', 'REFUND', 'ADJUSTMENT')),
    CONSTRAINT ck_billing_ledger_method CHECK (method IN ('SSLCOMMERZ', 'BANK_TRANSFER'))
);

CREATE INDEX ix_billing_ledger_month ON billing_ledger (created_at);
CREATE UNIQUE INDEX uq_billing_ledger_payment ON billing_ledger (invoice_id) WHERE type = 'PAYMENT';

SELECT mojud_enable_tenant_rls('billing_ledger');

REVOKE UPDATE, DELETE ON billing_ledger FROM mojud_app, mojud_system;
