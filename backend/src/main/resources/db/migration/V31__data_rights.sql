-- Data rights (implementation-plan.md 12b): deleting a business (after a 7-day undo window) and
-- deleting a customer.

-- A deletion asked for by the owner; the business is deleted when it falls due, unless cancelled.
ALTER TABLE businesses
    ADD COLUMN deletion_requested_at timestamptz,
    ADD COLUMN deletion_requested_by uuid REFERENCES users (id),
    ADD COLUMN deletion_due_at       timestamptz,
    ADD CONSTRAINT ck_businesses_deletion CHECK ((deletion_requested_at IS NULL) = (deletion_due_at IS NULL));

-- What outlives a deleted business: the admin log keeps the entry (without the link), and the trial
-- claim stays (or deleting a business would earn a new trial), without the link either.
ALTER TABLE admin_audit DROP CONSTRAINT admin_audit_target_business_id_fkey;
ALTER TABLE admin_audit ADD CONSTRAINT fk_admin_audit_business FOREIGN KEY (target_business_id)
    REFERENCES businesses (id) ON DELETE SET NULL;
ALTER TABLE trial_claims ALTER COLUMN business_id DROP NOT NULL;
ALTER TABLE trial_claims DROP CONSTRAINT trial_claims_business_id_fkey;
ALTER TABLE trial_claims ADD CONSTRAINT fk_trial_claims_business FOREIGN KEY (business_id)
    REFERENCES businesses (id) ON DELETE SET NULL;

-- A deleted customer's finished orders stay (sales still add up), anonymised, without the link.
ALTER TABLE orders ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE orders DROP CONSTRAINT fk_orders_customer;
ALTER TABLE orders ADD CONSTRAINT fk_orders_customer FOREIGN KEY (business_id, customer_id)
    REFERENCES customers (business_id, id) ON DELETE SET NULL (customer_id);

-- Deletes one business and everything it owns, from every table with a business_id (whatever tables
-- exist when it runs), children before parents, then the business itself. Runs as the schema owner:
-- append-only tables refuse DELETE to the app and system roles. Row-level security applies to the
-- owner too (FORCE), so it works as that business. Callable by the system role only.
CREATE FUNCTION mojud_delete_business(target uuid) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    remaining text[];
    still text[];
    t text;
    deleted integer := 0;
    n integer;
    pass integer := 0;
BEGIN
    PERFORM set_config('app.business_id', target::text, true);
    -- The business row points at its own data (its logo upload): let go first, as it is deleted last.
    UPDATE businesses SET logo_file_id = NULL WHERE id = target;
    SELECT array_agg(c.relname ORDER BY c.relname) INTO remaining
    FROM pg_class c
    JOIN pg_namespace ns ON ns.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'business_id' AND NOT a.attisdropped
    WHERE ns.nspname = 'public' AND c.relkind = 'r' AND c.relname NOT IN ('trial_claims');
    -- Tables referenced by others are emptied in a later pass; each pass deletes what it can.
    WHILE remaining IS NOT NULL AND array_length(remaining, 1) > 0 LOOP
        pass := pass + 1;
        IF pass > 50 THEN
            RAISE EXCEPTION 'mojud_delete_business: could not order the deletes (left: %)', remaining;
        END IF;
        still := ARRAY[]::text[];
        FOREACH t IN ARRAY remaining LOOP
            BEGIN
                EXECUTE format('DELETE FROM %I WHERE business_id = $1', t) USING target;
                GET DIAGNOSTICS n = ROW_COUNT;
                deleted := deleted + n;
            EXCEPTION WHEN foreign_key_violation THEN
                still := still || t;
            END;
        END LOOP;
        remaining := still;
    END LOOP;
    DELETE FROM businesses WHERE id = target;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN deleted + n;
END;
$$;

REVOKE ALL ON FUNCTION mojud_delete_business(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mojud_delete_business(uuid) TO mojud_system;
