-- Plans, the trial and limits (implementation-plan.md 10a, backend-plan.md §8).

-- The same for every business: no business_id, no row-level security, read-only for the app
-- (changed by migrations now, by the admin console later). A null limit means unlimited.
CREATE TABLE plans (
    code          text    PRIMARY KEY,
    name_en       text    NOT NULL,
    name_bn       text    NOT NULL,
    -- Poisha a month; 0 for the trial.
    price_monthly bigint  NOT NULL,
    is_public     boolean NOT NULL,
    limits        jsonb   NOT NULL,

    CONSTRAINT ck_plans_code CHECK (code IN ('TRIAL', 'STARTER', 'PRO')),
    CONSTRAINT ck_plans_price CHECK (price_monthly >= 0)
);

REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON plans FROM mojud_app, mojud_system;

-- ai_replies on the trial is a proposed cap (backend-plan.md §8) protecting AI cost (R2).
INSERT INTO plans (code, name_en, name_bn, price_monthly, is_public, limits) VALUES
    ('TRIAL', 'Pro trial', 'প্রো ট্রায়াল', 0, false,
     '{"variants": 5000, "orders_per_month": null, "staff_seats": 10, "import_rows": null, "report_days": null,
       "exports": true, "inbox_channels": 2, "ai_replies": 100}'),
    ('STARTER', 'Starter', 'স্টার্টার', 99000, true,
     '{"variants": 500, "orders_per_month": 2000, "staff_seats": 3, "import_rows": null, "report_days": 90,
       "exports": false, "inbox_channels": 1, "ai_replies": 0}'),
    ('PRO', 'Pro', 'প্রো', 299000, true,
     '{"variants": 5000, "orders_per_month": null, "staff_seats": 10, "import_rows": null, "report_days": null,
       "exports": true, "inbox_channels": 2, "ai_replies": 1000}');

-- One per business.
CREATE TABLE subscriptions (
    business_id          uuid        PRIMARY KEY REFERENCES businesses (id),
    plan_code            text        NOT NULL REFERENCES plans (code),
    -- TRIALING, ACTIVE, GRACE, READ_ONLY, SUSPENDED (moved by the daily job, 10b).
    status               text        NOT NULL,
    trial_ends_at        timestamptz,
    current_period_start timestamptz,
    current_period_end   timestamptz,
    grace_ends_at        timestamptz,
    -- A downgrade waits for the renewal (10b).
    pending_plan_code    text        REFERENCES plans (code),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL,

    CONSTRAINT ck_subscriptions_status CHECK (status IN ('TRIALING', 'ACTIVE', 'GRACE', 'READ_ONLY', 'SUSPENDED'))
);

SELECT mojud_enable_tenant_rls('subscriptions');

-- Monthly counters only (orders, later AI replies); variants and seats are counted live.
CREATE TABLE usage_counters (
    business_id uuid NOT NULL REFERENCES businesses (id),
    metric      text NOT NULL,
    -- The Dhaka month, "2026-11".
    period      text NOT NULL,
    used        int  NOT NULL,

    PRIMARY KEY (business_id, metric, period),
    CONSTRAINT ck_usage_counters_used CHECK (used >= 0),
    CONSTRAINT ck_usage_counters_period CHECK (period ~ '^[0-9]{4}-[0-9]{2}$')
);

SELECT mojud_enable_tenant_rls('usage_counters');

-- One trial per owner: by their phone ("phone:+8801..."), or their account when they have none
-- ("user:<id>"). The key is unique across all businesses, though each row is only visible to its own.
CREATE TABLE trial_claims (
    claim_key   text        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    claimed_at  timestamptz NOT NULL
);

SELECT mojud_enable_tenant_rls('trial_claims');
