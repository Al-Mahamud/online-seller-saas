-- A tenant table used only by TenantIsolationTests, set up exactly like real tenant tables.
CREATE TABLE IF NOT EXISTS rls_probe (
    id          uuid PRIMARY KEY,
    business_id uuid NOT NULL,
    note        text NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'rls_probe') THEN
        PERFORM mojud_enable_tenant_rls('rls_probe');
    END IF;
END
$$;
