package com.mojud.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAccounts.Account;
import com.mojud.support.TestBusinesses;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The audit log of sensitive actions (implementation-plan.md 2c). */
@IntegrationTest
class AuditLogTests {

    private static final Pattern INVITE_LINK = Pattern.compile("/invites#([A-Za-z0-9_-]+)");

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    DataSource dataSource;

    @Test
    void sensitiveActionsAreRecordedNewestFirst() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        UUID business = TestBusinesses.create(owner, "Audited Shop");
        owner.patch("/api/v1/business", Map.of("name", "Audited Shop Ltd")).expectStatus(200);
        owner.patch("/api/v1/business/settings", Map.of("stockCommitPoint", "CONFIRM")).expectStatus(200);
        ApiClient staff = new ApiClient(port);
        Account staffAccount = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", staffAccount.email(), "role", "STAFF")).expectStatus(201);
        staff.post("/api/v1/invites/accept", Map.of("token", token(staffAccount.email()))).expectStatus(200);
        UUID staffId = TestBusinesses.userId(staff);
        owner.patch("/api/v1/staff/" + staffId, Map.of("role", "MANAGER")).expectStatus(204);
        owner.delete("/api/v1/staff/" + staffId).expectStatus(204);

        ApiResponse log = owner.get("/api/v1/business/audit-log").expectStatus(200);

        assertThat(log.<List<String>>json("$.items[*].action")).containsExactly(
                "staff.removed", "staff.role_changed", "staff.joined", "staff.invited",
                "business.settings_changed", "business.updated", "business.created");
        assertThat(log.<String>json("$.items[0].entityId")).isEqualTo(staffId.toString());
        assertThat(log.<String>json("$.items[1].details.from")).isEqualTo("STAFF");
        assertThat(log.<String>json("$.items[1].details.to")).isEqualTo("MANAGER");
        assertThat(log.<String>json("$.items[2].actor.id")).isEqualTo(staffId.toString());
        assertThat(log.<String>json("$.items[3].details.to")).isEqualTo(staffAccount.email());
        assertThat(log.<Map<String, Object>>json("$.items[4].details.changes"))
                .containsOnlyKeys("stockCommitPoint");
        assertThat(log.<String>json("$.items[5].details.changes.name.from")).isEqualTo("Audited Shop");
        assertThat(log.<String>json("$.items[6].actor.name")).isEqualTo("Test Seller");
        assertThat(log.<String>json("$.items[6].entityId")).isEqualTo(business.toString());
    }

    @Test
    void changesThatChangeNothingAreNotRecorded() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Quiet Shop");

        owner.patch("/api/v1/business/settings", Map.of("stockCommitPoint", "SHIP")).expectStatus(200);
        owner.patch("/api/v1/business", Map.of("name", "Quiet Shop")).expectStatus(200);

        assertThat(owner.get("/api/v1/business/audit-log").<List<String>>json("$.items[*].action"))
                .containsExactly("business.created");
    }

    @Test
    void theLogIsPagedNewestFirst() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Busy Shop");
        for (int i = 1; i <= 4; i++) {
            owner.patch("/api/v1/business/settings", Map.of("lowStockDefault", i)).expectStatus(200);
        }

        ApiResponse first = owner.get("/api/v1/business/audit-log?limit=2").expectStatus(200);
        ApiResponse second = owner.get("/api/v1/business/audit-log?limit=2&cursor=" + first.<String>json("$.nextCursor"))
                .expectStatus(200);
        ApiResponse last = owner.get("/api/v1/business/audit-log?limit=2&cursor=" + second.<String>json("$.nextCursor"))
                .expectStatus(200);

        assertThat(first.<List<Integer>>json("$.items[*].details.changes.lowStockDefault.to")).containsExactly(4, 3);
        assertThat(second.<List<Integer>>json("$.items[*].details.changes.lowStockDefault.to")).containsExactly(2, 1);
        assertThat(last.<List<String>>json("$.items[*].action")).containsExactly("business.created");
        assertThat(last.<String>json("$.nextCursor")).isNull();
    }

    @Test
    void onlyTheOwnerReadsTheLog() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Private Shop");
        ApiClient manager = new ApiClient(port);
        Account account = TestAccounts.signUp(manager, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "MANAGER")).expectStatus(201);
        manager.post("/api/v1/invites/accept", Map.of("token", token(account.email()))).expectStatus(200);

        manager.get("/api/v1/business/audit-log").expectError(403, "access.denied");
    }

    @Test
    void entriesCannotBeChangedOrDeleted() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        UUID business = TestBusinesses.create(owner, "Permanent Shop");
        JdbcClient jdbc = JdbcClient.create(dataSource);

        assertThatThrownBy(() -> TenantScope.run(business, () ->
                jdbc.sql("update audit_logs set action = 'nothing.happened' where business_id = ?").param(business).update()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> TenantScope.run(business, () ->
                jdbc.sql("delete from audit_logs where business_id = ?").param(business).update()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }

    private String token(String destination) {
        List<CapturedMessages.Message> received = messages.to(destination);
        Matcher link = INVITE_LINK.matcher(received.get(received.size() - 1).body());
        assertThat(link.find()).isTrue();
        return link.group(1);
    }
}
