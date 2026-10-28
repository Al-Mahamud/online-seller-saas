-- The subscription lifecycle (implementation-plan.md 10b): when read-only began, for the 60- and
-- 85-day warnings and the 90-day data deletion (shipped disabled).
ALTER TABLE subscriptions ADD COLUMN read_only_since timestamptz;
ALTER TABLE subscriptions ADD CONSTRAINT ck_subscriptions_read_only
    CHECK ((status = 'READ_ONLY') = (read_only_since IS NOT NULL) OR status = 'SUSPENDED');

CREATE INDEX ix_subscriptions_status ON subscriptions (status);
