-- Row-level security for tenant tables (backend-plan.md §4, layer 2).
--
-- The application sets app.business_id on every connection it hands out (TenantAwareDataSource):
-- the current business, or '' when there is none. An empty or missing value matches no rows, so a
-- forgotten tenant context fails closed.

CREATE FUNCTION mojud_current_business() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.business_id', true), '')::uuid $$;

-- Every tenant table calls this right after CREATE TABLE, so all policies have the same text:
--   SELECT mojud_enable_tenant_rls('orders');
-- FORCE makes the policy apply to the table owner too. SchemaRulesTests fails the build if a table
-- with a business_id column has not called it.
CREATE FUNCTION mojud_enable_tenant_rls(target regclass) RETURNS void
    LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    EXECUTE format('CREATE POLICY tenant_isolation ON %s '
                   'USING (business_id = mojud_current_business()) '
                   'WITH CHECK (business_id = mojud_current_business())', target);
END
$$;

REVOKE EXECUTE ON FUNCTION mojud_enable_tenant_rls(regclass) FROM PUBLIC;
