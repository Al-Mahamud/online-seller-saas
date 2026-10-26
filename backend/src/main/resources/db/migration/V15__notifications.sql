-- In-app notifications (implementation-plan.md 4b). One notification is for a whole business,
-- shown to members with at least its audience role; each member's "read" is kept separately.

CREATE TABLE notifications (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    type        text        NOT NULL,
    -- The lowest role that sees it.
    audience    text        NOT NULL,
    payload     jsonb       NOT NULL,
    -- At most one notification per key, e.g. one low-stock alert per variant per day.
    dedupe_key  text,
    created_at  timestamptz NOT NULL,

    CONSTRAINT uq_notifications_business_id UNIQUE (business_id, id),
    CONSTRAINT ck_notifications_audience CHECK (audience IN ('OWNER', 'MANAGER', 'STAFF'))
);

CREATE UNIQUE INDEX uq_notifications_dedupe ON notifications (business_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX ix_notifications_newest ON notifications (business_id, created_at DESC, id DESC);

SELECT mojud_enable_tenant_rls('notifications');

CREATE TABLE notification_reads (
    business_id     uuid        NOT NULL,
    notification_id uuid        NOT NULL,
    user_id         uuid        NOT NULL REFERENCES users (id),
    read_at         timestamptz NOT NULL,

    PRIMARY KEY (notification_id, user_id),
    CONSTRAINT fk_notification_reads_notification FOREIGN KEY (business_id, notification_id)
        REFERENCES notifications (business_id, id) ON DELETE CASCADE
);

SELECT mojud_enable_tenant_rls('notification_reads');
