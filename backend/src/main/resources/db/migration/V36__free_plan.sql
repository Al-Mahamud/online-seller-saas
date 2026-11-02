-- Three plans, no trial: every business starts on Free; Starter ৳499 and Pro ৳899 are paid.
-- Staff seats count members other than the owner, so Free's 0 means the owner alone.
ALTER TABLE plans DROP CONSTRAINT ck_plans_code;
ALTER TABLE plans ADD CONSTRAINT ck_plans_code CHECK (code IN ('TRIAL', 'FREE', 'STARTER', 'PRO'));

INSERT INTO plans (code, name_en, name_bn, price_monthly, is_public, limits) VALUES
    ('FREE', 'Free', 'ফ্রি', 0, true,
     '{"variants": 50, "orders_per_month": 60, "staff_seats": 0, "import_rows": null, "report_days": 30,
       "exports": false, "inbox_channels": 1, "ai_replies": 0}');

UPDATE plans SET price_monthly = 49900, limits = limits || '{"staff_seats": 10, "report_days": 365}' WHERE code = 'STARTER';
UPDATE plans SET price_monthly = 89900, limits = limits || '{"staff_seats": null}' WHERE code = 'PRO';

-- The trial is gone: a business on it, or read-only after it, is on Free.
UPDATE subscriptions
SET plan_code = 'FREE', status = 'ACTIVE', trial_ends_at = NULL, current_period_start = NULL, current_period_end = NULL,
    grace_ends_at = NULL, read_only_since = NULL, pending_plan_code = NULL, updated_at = now(), version = version + 1
WHERE plan_code = 'TRIAL' AND status IN ('TRIALING', 'READ_ONLY');

-- One trial per owner no longer means anything.
DROP TABLE trial_claims;
