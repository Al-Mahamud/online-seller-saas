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
    void theLogIsNarrowedByWhoWhatWhenAndWordsWithASummary() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Filtered Shop");
        UUID ownerId = TestBusinesses.userId(owner);
        String staffLogin = TestAccounts.newUserId();
        ApiResponse added = owner.post("/api/v1/staff", Map.of("name", "Sumi", "phone", TestAccounts.newPhone(),
                "userId", staffLogin, "role", "STAFF")).expectStatus(201);
        String staffId = added.json("$.member.userId");
        ApiClient staff = new ApiClient(port);
        staff.post("/api/v1/auth/login", Map.of("userId", staffLogin, "password", added.json("$.startingPassword")))
                .expectStatus(200);
        staff.post("/api/v1/me/first-password", Map.of("newPassword", "a password of mine")).expectStatus(200);
        String customer = staff.post("/api/v1/customers", Map.of("name", "Zahid", "phone", TestAccounts.newPhone()))
                .expectStatus(201).json("$.id");
        owner.delete("/api/v1/customers/" + customer).expectStatus(204);
        owner.patch("/api/v1/business", Map.of("name", "Filtered Shop Ltd")).expectStatus(200);

        // About a person: their name comes with it.
        ApiResponse team = owner.get("/api/v1/business/audit-log?kind=TEAM").expectStatus(200);
        assertThat(team.<List<String>>json("$.items[*].action")).containsExactly("staff.added");
        assertThat(team.<String>json("$.items[0].subject")).isEqualTo("Sumi");
        assertThat(team.<String>json("$.items[0].kind")).isEqualTo("TEAM");
        // Words in the details; and a deleted customer keeps their name in the log.
        ApiResponse found = owner.get("/api/v1/business/audit-log?q=zahid").expectStatus(200);
        assertThat(found.<List<String>>json("$.items[*].action")).containsExactly("customer.deleted");
        assertThat(found.<String>json("$.items[0].details.name")).isEqualTo("Zahid");
        // Who did it.
        assertThat(owner.get("/api/v1/business/audit-log?actor=" + ownerId).expectStatus(200)
                .<List<String>>json("$.items[*].action"))
                .containsExactly("business.updated", "customer.deleted", "staff.added", "business.created");
        assertThat(owner.get("/api/v1/business/audit-log?actor=SYSTEM").expectStatus(200).<Integer>json("$.totalItems"))
                .isZero();
        // When: today is in, tomorrow is not.
        java.time.LocalDate today = java.time.LocalDate.now(com.mojud.shared.time.DhakaTime.ZONE);
        assertThat(owner.get("/api/v1/business/audit-log?from=" + today + "&to=" + today).expectStatus(200)
                .<Integer>json("$.totalItems")).isEqualTo(4);
        assertThat(owner.get("/api/v1/business/audit-log?from=" + today.plusDays(1)).expectStatus(200)
                .<Integer>json("$.totalItems")).isZero();
        owner.get("/api/v1/business/audit-log?actor=nobody").expectError(400, "validation.failed");
        owner.get("/api/v1/business/audit-log?from=" + today + "&to=" + today.minusDays(1))
                .expectError(400, "validation.failed");

        ApiResponse summary = owner.get("/api/v1/business/audit-log/summary").expectStatus(200);
        assertThat(summary.<Integer>json("$.today")).isEqualTo(4);
        assertThat(summary.<Integer>json("$.lastSevenDays")).isEqualTo(4);
        assertThat(summary.<List<Integer>>json("$.people[*].count")).containsExactly(4);
        assertThat(summary.<List<String>>json("$.kinds[*].kind")).containsExactlyInAnyOrder("SETTINGS", "TEAM", "CUSTOMERS");
        assertThat(summary.<List<Boolean>>json("$.actors[*].member")).containsExactly(true);

        // Someone who has left is marked so, and stays in the log.
        owner.delete("/api/v1/staff/" + staffId).expectStatus(204);
        staff.post("/api/v1/auth/logout", Map.of());
        assertThat(owner.get("/api/v1/business/audit-log?kind=TEAM").expectStatus(200)
                .<String>json("$.items[0].subject")).isEqualTo("Sumi");
        owner.get("/api/v1/staff").expectStatus(200);
        staff.get("/api/v1/business/audit-log/summary").expectError(401, "auth.required");
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

        ApiResponse first = owner.get("/api/v1/business/audit-log?size=2").expectStatus(200);
        ApiResponse second = owner.get("/api/v1/business/audit-log?size=2&page=2").expectStatus(200);
        ApiResponse last = owner.get("/api/v1/business/audit-log?size=2&page=3").expectStatus(200);

        assertThat(first.<List<Integer>>json("$.items[*].details.changes.lowStockDefault.to")).containsExactly(4, 3);
        assertThat(second.<List<Integer>>json("$.items[*].details.changes.lowStockDefault.to")).containsExactly(2, 1);
        assertThat(last.<List<String>>json("$.items[*].action")).containsExactly("business.created");
        assertThat(last.<Integer>json("$.totalItems")).isEqualTo(5);
        assertThat(last.<Integer>json("$.totalPages")).isEqualTo(3);
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
