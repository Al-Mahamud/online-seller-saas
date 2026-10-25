package com.mojud.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Rules every migration must follow, checked against the migrated schema so a new table cannot
 * slip through review without them.
 */
@IntegrationTest
class SchemaRulesTests {

    @Autowired
    SystemDatabase systemDatabase;

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
