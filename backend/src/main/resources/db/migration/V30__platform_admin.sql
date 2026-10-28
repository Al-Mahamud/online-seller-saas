-- Platform administration (implementation-plan.md 12a).

-- What platform administrators did, across businesses (and to plans, which belong to none).
-- Written by the system role in the transaction of the action; never changed. The business an
-- action touched is target_business_id: this is not a business's own table.
CREATE TABLE admin_audit (
    id                 uuid        PRIMARY KEY,
    admin_id           uuid        NOT NULL REFERENCES users (id),
    action             text        NOT NULL,
    target_type        text        NOT NULL,
    target_id          text        NOT NULL,
    target_business_id uuid        REFERENCES businesses (id),
    details            jsonb       NOT NULL,
    client_ip          text,
    created_at         timestamptz NOT NULL
);

CREATE INDEX ix_admin_audit_newest ON admin_audit (created_at DESC);
CREATE INDEX ix_admin_audit_business ON admin_audit (target_business_id, created_at DESC);

REVOKE ALL ON admin_audit FROM mojud_app;
REVOKE UPDATE, DELETE ON admin_audit FROM mojud_system;

-- Suspension remembers the status it interrupted, to restore it when lifted.
ALTER TABLE subscriptions ADD COLUMN suspended_from text;
ALTER TABLE subscriptions ADD CONSTRAINT ck_subscriptions_suspended
    CHECK ((status = 'SUSPENDED') = (suspended_from IS NOT NULL));

-- Administrators edit plans (names, prices, limits) through the system role; the app still cannot.
GRANT UPDATE (name_en, name_bn, price_monthly, is_public, limits) ON plans TO mojud_system;
