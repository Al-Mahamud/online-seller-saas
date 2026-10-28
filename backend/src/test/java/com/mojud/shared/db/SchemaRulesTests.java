package com.mojud.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.support.CapturedMessages;
import com.mojud.support.CrossTenant;
import com.mojud.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rules every migration must follow, checked against the migrated schema so a new table cannot
 * slip through review without them.
 */
@IntegrationTest
class SchemaRulesTests {

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    DataSource dataSource;

    @Autowired
    CapturedMessages messages;

    @LocalServerPort
    int port;

    @Test
    void everyTableWithABusinessIdHasForcedRowLevelSecurity() {
        List<String> unprotected = systemDatabase.jdbc().sql("""
                        select c.relname
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        join pg_attribute a on a.attrelid = c.oid and a.attname = 'business_id' and not a.attisdropped
                        where n.nspname = 'public' and c.relkind in ('r', 'p')
                          and not (c.relrowsecurity and c.relforcerowsecurity
                                   and exists (select 1 from pg_policies p
                                               where p.schemaname = 'public' and p.tablename = c.relname
                                                 and p.policyname = 'tenant_isolation'))
                        order by c.relname
                        """)
                .query(String.class)
                .list();

        assertThat(unprotected).as("tables with business_id but no tenant_isolation policy").isEmpty();
    }

    @Test
    void theBusinessesTableIsProtectedByItsOwnId() {
        Boolean protectedTable = systemDatabase.jdbc().sql("""
                        select c.relrowsecurity and c.relforcerowsecurity
                               and exists (select 1 from pg_policies p
                                           where p.tablename = 'businesses' and p.policyname = 'tenant_isolation'
                                             and p.qual like '%id = mojud_current_business()%')
                        from pg_class c where c.relname = 'businesses'
                        """)
                .query(Boolean.class)
                .single();

        assertThat(protectedTable).isTrue();
    }

    @Test
    void withoutABusinessTheApplicationRoleReadsNothingFromAnyBusinessTable() {
        // Rows in every business table, whatever other tests ran first.
        CrossTenant.Tenant tenant = CrossTenant.setUp(port, messages, (businessId, userId) -> systemDatabase.jdbc()
                .sql("select id from memberships where business_id = ? and user_id = ?")
                .params(businessId, userId).query(UUID.class).single());
        TenantScope.run(tenant.businessId(), () -> JdbcClient.create(dataSource)
                .sql("insert into rls_probe (id, business_id, note) values (?, ?, 'schema rules')")
                .params(Ids.newId(), tenant.businessId()).update());
        // Ledger rows come from paid invoices (gateway or admin); one for the fixture's open invoice.
        systemDatabase.jdbc().sql("""
                        insert into billing_ledger (id, business_id, invoice_id, type, amount, vat_amount, method, reference, created_at)
                        values (?, ?, ?, 'ADJUSTMENT', 0, 0, 'BANK_TRANSFER', 'schema rules', now())
                        """)
                .params(Ids.newId(), tenant.businessId(), tenant.invoiceId()).update();
        List<String> tables = systemDatabase.jdbc().sql("""
                        select distinct c.relname
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        left join pg_attribute a on a.attrelid = c.oid and a.attname = 'business_id' and not a.attisdropped
                        where n.nspname = 'public' and c.relkind in ('r', 'p')
                          and (a.attname is not null or c.relname = 'businesses')
                        order by c.relname
                        """)
                .query(String.class)
                .list();
        JdbcClient app = JdbcClient.create(dataSource);

        assertThat(tables).contains("businesses", "memberships", "invites", "audit_logs");
        for (String table : tables) {
            assertThat(systemDatabase.jdbc().sql("select count(*) from " + table).query(Long.class).single())
                    .as("%s has rows to hide", table).isPositive();
            assertThat(app.sql("select count(*) from " + table).query(Long.class).single())
                    .as("rows of %s visible with no business selected", table).isZero();
        }
    }

    @Test
    void theApplicationRoleOwnsNothing() {
        List<String> owned = systemDatabase.jdbc().sql("""
                        select c.relname from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = 'public' and pg_get_userbyid(c.relowner) in ('mojud_app', 'mojud_system')
                        """)
                .query(String.class)
                .list();

        assertThat(owned).isEmpty();
    }
}
