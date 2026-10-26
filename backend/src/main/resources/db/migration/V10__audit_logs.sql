-- The audit log of sensitive actions (implementation-plan.md 2c, backend-plan.md §5.5).
-- Every entry belongs to one business. Platform-admin actions get their own log in Phase 12a.

CREATE TABLE audit_logs (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES businesses (id),
    -- The signed-in user who acted; null only for the system itself (jobs, later phases).
    actor_id    uuid        REFERENCES users (id),
    -- A dotted name such as staff.role_changed.
    action      text        NOT NULL,
    entity_type text        NOT NULL,
    entity_id   uuid,
    -- What changed: before and after values, or a short summary.
    details     jsonb       NOT NULL DEFAULT '{}',
    client_ip   text,
    created_at  timestamptz NOT NULL
);

CREATE INDEX ix_audit_logs_newest ON audit_logs (business_id, created_at DESC, id DESC);

SELECT mojud_enable_tenant_rls('audit_logs');

-- Append-only: what happened stays written.
REVOKE UPDATE, DELETE ON audit_logs FROM mojud_app, mojud_system;
