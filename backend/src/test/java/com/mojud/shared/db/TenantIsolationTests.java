package com.mojud.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Layer 2 of tenant isolation (backend-plan.md §4): PostgreSQL itself keeps businesses apart, even
 * if application code forgets a filter. Uses {@code rls_probe}, a test-only table set up exactly
 * like real tenant tables.
 */
@IntegrationTest
class TenantIsolationTests {

    @Autowired
    DataSource dataSource;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    TransactionTemplate transactions;

    JdbcClient jdbc;
    UUID businessA;
    UUID businessB;

    @BeforeEach
    void twoBusinessesWithOneRowEach() {
        jdbc = JdbcClient.create(dataSource);
        businessA = Ids.newId();
        businessB = Ids.newId();
        TenantScope.run(businessA, () -> insert(businessA, "a"));
        TenantScope.run(businessB, () -> insert(businessB, "b"));
    }

    @Test
    void eachBusinessSeesOnlyItsOwnRows() {
        assertThat(TenantScope.call(businessA, this::visibleNotes)).containsExactly("a");
        assertThat(TenantScope.call(businessB, this::visibleNotes)).containsExactly("b");
    }

    @Test
    void withoutABusinessNothingIsVisible() {
        assertThat(visibleNotes()).isEmpty();
    }

    @Test
    void aPooledConnectionDoesNotKeepThePreviousBusiness() {
        for (int i = 0; i < 20; i++) {
            assertThat(TenantScope.call(businessA, this::visibleNotes)).containsExactly("a");
            assertThat(visibleNotes()).isEmpty();
        }
    }

    @Test
    void rowsCannotBeWrittenForAnotherBusiness() {
        assertThatThrownBy(() -> TenantScope.run(businessA, () -> insert(businessB, "sneaky")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void rowsCannotBeMovedToAnotherBusiness() {
        assertThatThrownBy(() -> TenantScope.run(businessA, () -> jdbc
                        .sql("update rls_probe set business_id = ? where business_id = ?")
                        .params(businessB, businessA)
                        .update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void anotherBusinessesRowsCannotBeChangedOrDeleted() {
        int updated = TenantScope.call(businessA, () -> jdbc
                .sql("update rls_probe set note = 'changed' where business_id = ?").param(businessB).update());
        int deleted = TenantScope.call(businessA, () -> jdbc
                .sql("delete from rls_probe where business_id = ?").param(businessB).update());

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(TenantScope.call(businessB, this::visibleNotes)).containsExactly("b");
    }

    @Test
    void theSystemRoleSeesEveryBusiness() {
        List<String> notes = systemDatabase.jdbc()
                .sql("select note from rls_probe where business_id in (?, ?) order by note")
                .params(businessA, businessB)
                .query(String.class)
                .list();

        assertThat(notes).containsExactly("a", "b");
    }

    @Test
    void theApplicationRoleCannotChangeTheSchemaOrBypassSecurity() {
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo("mojud_app");
        assertThat(jdbc.sql("select rolbypassrls or rolsuper from pg_roles where rolname = current_user")
                .query(Boolean.class).single()).isFalse();
        assertThatThrownBy(() -> jdbc.sql("create table sneaky (id int)").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("alter table rls_probe disable row level security").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("must be owner");
        assertThatThrownBy(() -> jdbc.sql("select count(*) from flyway_schema_history").query(Long.class).single())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void switchingBusinessInsideATransactionIsRefused() {
        assertThatThrownBy(() -> TenantScope.run(businessA, () -> transactions.executeWithoutResult(status ->
                        TenantScope.run(businessB, this::visibleNotes))))
                .isInstanceOf(IllegalStateException.class);
    }

    private void insert(UUID businessId, String note) {
        jdbc.sql("insert into rls_probe (id, business_id, note) values (?, ?, ?)")
                .params(Ids.newId(), businessId, note)
                .update();
    }

    private List<String> visibleNotes() {
        return jdbc.sql("select note from rls_probe where business_id in (?, ?) order by note")
                .params(businessA, businessB)
                .query(String.class)
                .list();
    }
}
