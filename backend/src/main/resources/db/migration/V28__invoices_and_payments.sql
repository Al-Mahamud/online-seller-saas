-- Paying for a plan through SSLCommerz (implementation-plan.md 11a, backend-plan.md §9).

-- Invoice numbers across the platform: MJ-000001.
CREATE SEQUENCE invoice_numbers;

CREATE TABLE invoices (
    id           uuid        PRIMARY KEY,
    business_id  uuid        NOT NULL REFERENCES businesses (id),
    number       text        NOT NULL UNIQUE,
    -- SUBSCRIPTION (a plan for some months), UPGRADE (11b), AI_PACK (R2).
    kind         text        NOT NULL,
    plan_code    text        NOT NULL REFERENCES plans (code),
    months       int         NOT NULL,
    -- Poisha, VAT included; vat_amount is the VAT part (billing.vat-rate, 0 for now).
    amount       bigint      NOT NULL,
    vat_amount   bigint      NOT NULL,
    -- OPEN until paid; VOID if never paid (11b); REFUNDED (11b).
    status       text        NOT NULL,
    -- The paid period, set when paid.
    period_start timestamptz,
    period_end   timestamptz,
    created_by   uuid        REFERENCES users (id),
    created_at   timestamptz NOT NULL,
    paid_at      timestamptz,

    CONSTRAINT uq_invoices_business_id UNIQUE (business_id, id),
    CONSTRAINT ck_invoices_kind CHECK (kind IN ('SUBSCRIPTION', 'UPGRADE', 'AI_PACK')),
    CONSTRAINT ck_invoices_months CHECK (months IN (1, 6, 12) OR kind <> 'SUBSCRIPTION'),
    CONSTRAINT ck_invoices_money CHECK (amount > 0 AND vat_amount >= 0 AND vat_amount <= amount),
    CONSTRAINT ck_invoices_status CHECK (status IN ('OPEN', 'PAID', 'VOID', 'REFUNDED')),
    CONSTRAINT ck_invoices_paid CHECK ((status IN ('PAID', 'REFUNDED')) = (paid_at IS NOT NULL))
);

CREATE INDEX ix_invoices_business ON invoices (business_id, created_at DESC);

SELECT mojud_enable_tenant_rls('invoices');

-- One try at paying an invoice through a gateway; tran_id is what the gateway knows it by.
CREATE TABLE payment_attempts (
    id           uuid        PRIMARY KEY,
    business_id  uuid        NOT NULL,
    invoice_id   uuid        NOT NULL,
    gateway      text        NOT NULL,
    tran_id      text        NOT NULL UNIQUE,
    val_id       text,
    -- What we asked the gateway to charge (poisha).
    amount       bigint      NOT NULL,
    -- PENDING until the gateway's answer is validated: PAID, FAILED, CANCELLED, or INVALID (a
    -- callback that did not validate, or did not match the invoice).
    status       text        NOT NULL,
    -- The gateway's validation answer, as received.
    validation   jsonb,
    created_at   timestamptz NOT NULL,
    validated_at timestamptz,
    checked_at   timestamptz,

    CONSTRAINT fk_payment_attempts_invoice FOREIGN KEY (business_id, invoice_id) REFERENCES invoices (business_id, id),
    CONSTRAINT ck_payment_attempts_gateway CHECK (gateway IN ('SSLCOMMERZ', 'MANUAL')),
    CONSTRAINT ck_payment_attempts_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'INVALID'))
);

CREATE INDEX ix_payment_attempts_pending ON payment_attempts (status, created_at) WHERE status = 'PENDING';

SELECT mojud_enable_tenant_rls('payment_attempts');

-- Every call the gateway makes to us, stored before anything else is done with it. It may not
-- name a real payment (or any business), so it has no business_id and only the system role
-- writes and reads it.
CREATE TABLE gateway_callbacks (
    id          uuid        PRIMARY KEY,
    gateway     text        NOT NULL,
    -- IPN, or the browser's return: SUCCESS, FAIL, CANCEL.
    kind        text        NOT NULL,
    tran_id     text,
    body        text        NOT NULL,
    received_at timestamptz NOT NULL
);

CREATE INDEX ix_gateway_callbacks_tran ON gateway_callbacks (tran_id);

REVOKE ALL ON gateway_callbacks FROM mojud_app;
REVOKE UPDATE, DELETE ON gateway_callbacks FROM mojud_system;
