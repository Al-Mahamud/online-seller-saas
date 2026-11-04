package com.mojud.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Members the business adds itself: their account with a User ID it chose and a starting password,
 * replaced at the first sign-in; and what staff and managers may do, which the owner decides.
 */
@IntegrationTest
class TeamAccountTests {

    private static final String OWN_PASSWORD = "my very own password";

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    TestClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // Adding a member.

    @Test
    void anAddedMemberSignsInWithTheStartingPasswordAndMustSetTheirOwn() {
        ApiClient owner = owner("Kurti House");
        String phone = TestAccounts.newPhone();
        String userId = TestAccounts.newUserId();

        ApiResponse added = owner.post("/api/v1/staff", Map.of("name", "Nadia", "phone", phone, "userId", userId,
                "role", "STAFF")).expectStatus(201);
        String starting = added.json("$.startingPassword");
        assertThat(starting).matches("[A-Za-z2-9]{4}-[A-Za-z2-9]{4}");
        assertThat(added.<Boolean>json("$.existingAccount")).isFalse();
        assertThat(added.<String>json("$.sentBy")).isEqualTo("SMS");
        assertThat(added.<String>json("$.member.loginId")).isEqualTo(userId);
        assertThat(added.<Boolean>json("$.member.awaitingFirstSignIn")).isTrue();
        assertThat(added.<Boolean>json("$.member.canSendPassword")).isTrue();
        // The SMS has what they need to sign in.
        List<com.mojud.support.CapturedMessages.Message> sent = messages.to("+88" + phone);
        assertThat(sent.get(sent.size() - 1).body()).contains(userId, starting, "Kurti House");

        ApiClient member = new ApiClient(port);
        ApiResponse me = member.post("/api/v1/auth/login", Map.of("userId", userId, "password", starting))
                .expectStatus(200);
        assertThat(me.<Boolean>json("$.mustChangePassword")).isTrue();
        // Nothing but setting their own password until they have.
        member.get("/api/v1/orders").expectError(403, "auth.password_change_required");
        member.get("/api/v1/staff").expectError(403, "auth.password_change_required");
        // Only which business, to greet them.
        assertThat(member.get("/api/v1/business").expectStatus(200).<String>json("$.name")).isEqualTo("Kurti House");
        assertThat(member.get("/api/v1/me").expectStatus(200).<Boolean>json("$.mustChangePassword")).isTrue();
        member.post("/api/v1/me/first-password", Map.of("newPassword", "short")).expectError(400, "validation.failed");

        ApiResponse after = member.post("/api/v1/me/first-password", Map.of("newPassword", OWN_PASSWORD))
                .expectStatus(200);
        assertThat(after.<Boolean>json("$.mustChangePassword")).isFalse();
        ApiResponse business = member.get("/api/v1/business").expectStatus(200);
        assertThat(business.<String>json("$.role")).isEqualTo("STAFF");
        assertThat(business.<List<String>>json("$.permissions")).containsExactly("ORDERS", "STOCK", "CUSTOMERS");
        // Once only: it is a password of their own now, and the starting one is gone.
        member.post("/api/v1/me/first-password", Map.of("newPassword", OWN_PASSWORD)).expectError(403, "access.denied");
        new ApiClient(port).post("/api/v1/auth/login", Map.of("userId", userId, "password", starting))
                .expectError(401, "auth.invalid_credentials");
        new ApiClient(port).post("/api/v1/auth/login", Map.of("userId", userId, "password", OWN_PASSWORD))
                .expectStatus(200);

        ApiResponse team = owner.get("/api/v1/staff").expectStatus(200);
        assertThat(team.<List<Boolean>>json("$[?(@.loginId == '" + userId + "')].awaitingFirstSignIn"))
                .containsExactly(false);
    }

    @Test
    void aStartingPasswordStopsAfterSevenDaysAndTheBusinessSendsANewOne() {
        ApiClient owner = owner("Kurti House");
        String userId = TestAccounts.newUserId();
        ApiResponse added = owner.post("/api/v1/staff", Map.of("name", "Nadia", "phone", TestAccounts.newPhone(),
                "userId", userId, "role", "STAFF")).expectStatus(201);
        String memberId = added.json("$.member.userId");

        clock.advance(Duration.ofDays(8));
        new ApiClient(port).post("/api/v1/auth/login", Map.of("userId", userId, "password", added.json("$.startingPassword")))
                .expectError(401, "auth.starting_password_expired");

        ApiResponse fresh = owner.post("/api/v1/staff/" + memberId + "/new-password", Map.of()).expectStatus(200);
        assertThat(fresh.<String>json("$.sentBy")).isEqualTo("SMS");
        ApiClient member = new ApiClient(port);
        member.post("/api/v1/auth/login", Map.of("userId", userId, "password", fresh.json("$.startingPassword")))
                .expectStatus(200);
        member.post("/api/v1/me/first-password", Map.of("newPassword", OWN_PASSWORD)).expectStatus(200);
    }

    @Test
    void aNewPasswordSignsTheMemberOutEverywhere() {
        ApiClient owner = owner("Kurti House");
        Member member = member(owner, "STAFF");
        member.client.get("/api/v1/business").expectStatus(200);

        owner.post("/api/v1/staff/" + member.id + "/new-password", Map.of()).expectStatus(200);

        member.client.get("/api/v1/business").expectError(401, "auth.required");
        new ApiClient(port).post("/api/v1/auth/login", Map.of("userId", member.userId, "password", OWN_PASSWORD))
                .expectError(401, "auth.invalid_credentials");
    }

    @Test
    void someoneAlreadyOnMojudIsAddedWithTheirOwnAccount() {
        ApiClient owner = owner("Kurti House");
        ApiClient someone = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(someone, messages);

        ApiResponse added = owner.post("/api/v1/staff", Map.of("name", "Whoever", "phone", TestAccounts.newPhone(),
                "email", account.email(), "userId", TestAccounts.newUserId(), "role", "MANAGER")).expectStatus(201);

        assertThat(added.<Boolean>json("$.existingAccount")).isTrue();
        assertThat(added.<Object>json("$.startingPassword")).isNull();
        // Their own User ID is not shown to a business that typed a phone or email.
        assertThat(added.<String>json("$.member.name")).isEqualTo("Test Seller");
        String id = added.json("$.member.userId");
        assertThat(added.<Boolean>json("$.member.canSendPassword")).isFalse();
        owner.post("/api/v1/staff/" + id + "/new-password", Map.of()).expectError(409, "staff.password_not_yours");
        owner.post("/api/v1/staff", Map.of("name", "Again", "phone", TestAccounts.newPhone(), "email", account.email(),
                "userId", TestAccounts.newUserId(), "role", "STAFF")).expectError(409, "staff.already_member");

        // They see the business with their usual sign-in.
        assertThat(someone.get("/api/v1/businesses").expectStatus(200).<List<String>>json("$[*].name"))
                .contains("Kurti House");
    }

    @Test
    void theUserIdMustBeFreeAndValid() {
        ApiClient owner = owner("Kurti House");
        String taken = TestAccounts.newUserId();
        owner.post("/api/v1/staff", Map.of("name", "One", "phone", TestAccounts.newPhone(), "userId", taken,
                "role", "STAFF")).expectStatus(201);

        owner.post("/api/v1/staff", Map.of("name", "Two", "phone", TestAccounts.newPhone(), "userId", taken.toUpperCase(),
                "role", "STAFF")).expectError(400, "validation.failed");
        owner.post("/api/v1/staff", Map.of("name", "Two", "phone", TestAccounts.newPhone(), "userId", "no",
                "role", "STAFF")).expectError(400, "validation.failed");
        owner.post("/api/v1/staff", Map.of("name", "Two", "phone", TestAccounts.newPhone(), "userId", "admin",
                "role", "STAFF")).expectError(400, "validation.failed");
        owner.post("/api/v1/staff", Map.of("name", "Two", "phone", TestAccounts.newPhone(), "userId",
                TestAccounts.newUserId(), "role", "OWNER")).expectError(400, "validation.failed");
    }

    @Test
    void onFreeTheOwnerWorksAlone() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.createOnFree(owner, "Small Shop");

        owner.post("/api/v1/staff", Map.of("name", "Nadia", "phone", TestAccounts.newPhone(),
                "userId", TestAccounts.newUserId(), "role", "STAFF")).expectError(402, "plan.limit_reached");
    }

    // What each role may do.

    @Test
    void theOwnerDecidesWhatStaffAndManagersMayDo() {
        ApiClient owner = owner("Kurti House");
        Member staff = member(owner, "STAFF");
        Member manager = member(owner, "MANAGER");
        ApiResponse rules = staff.client.get("/api/v1/business/permissions").expectStatus(200);
        assertThat(rules.<List<String>>json("$.staff")).containsExactly("ORDERS", "STOCK", "CUSTOMERS");
        assertThat(rules.<List<String>>json("$.manager")).hasSize(7);
        String product = owner.post("/api/v1/products", Map.of("name", "Watch", "price", 500000, "costPrice", 300000))
                .expectStatus(201).json("$.id");
        assertThat(staff.client.get("/api/v1/products/" + product).expectStatus(200)
                .<Map<String, Object>>json("$.variants[0]")).doesNotContainKey("costPrice");

        manager.client.put("/api/v1/business/permissions", Map.of("staff", List.of(), "manager", List.of()))
                .expectError(403, "access.denied");
        owner.put("/api/v1/business/permissions", Map.of(
                "staff", List.of("ORDERS", "COSTS", "PRODUCTS"),
                "manager", List.of("ORDERS", "STOCK", "CUSTOMERS", "COSTS", "PRODUCTS", "TEAM"))).expectStatus(200);

        // Staff: no stock now, costs and products yes; customers can still be found for an order.
        staff.client.get("/api/v1/stock/low").expectError(403, "access.denied");
        staff.client.get("/api/v1/customers").expectStatus(200);
        staff.client.post("/api/v1/customers", Map.of("name", "Karim", "phone", TestAccounts.newPhone()))
                .expectError(403, "access.denied");
        assertThat(staff.client.get("/api/v1/products/" + product).expectStatus(200)
                .<Integer>json("$.variants[0].costPrice")).isEqualTo(300000);
        staff.client.post("/api/v1/products", Map.of("name", "Scarf", "price", 1000)).expectStatus(201);
        assertThat(staff.client.get("/api/v1/business").expectStatus(200).<List<String>>json("$.permissions"))
                .containsExactly("ORDERS", "COSTS", "PRODUCTS");
        // Managers: no settings now.
        manager.client.patch("/api/v1/business", Map.of("name", "Renamed")).expectError(403, "access.denied");
        manager.client.put("/api/v1/delivery-zones", Map.of("zones", List.of())).expectError(403, "access.denied");
        // The owner always may.
        owner.patch("/api/v1/business", Map.of("name", "Renamed")).expectStatus(200);

        owner.put("/api/v1/business/permissions", Map.of("staff", List.of("NOT_A_THING"), "manager", List.of()))
                .expectError(400, "validation.failed");
    }

    @Test
    void someoneWhoManagesTheTeamNeverGivesOrManagesARoleAboveTheirOwn() {
        ApiClient owner = owner("Kurti House");
        Member manager = member(owner, "MANAGER");
        Member staff = member(owner, "STAFF");
        owner.put("/api/v1/business/permissions", Map.of(
                "staff", List.of("ORDERS", "STOCK", "CUSTOMERS", "TEAM"),
                "manager", List.of("ORDERS", "STOCK", "CUSTOMERS", "COSTS", "PRODUCTS", "SETTINGS", "TEAM")))
                .expectStatus(200);

        staff.client.post("/api/v1/staff", Map.of("name", "Boss", "phone", TestAccounts.newPhone(),
                "userId", TestAccounts.newUserId(), "role", "MANAGER")).expectError(403, "staff.role_above_yours");
        String added = staff.client.post("/api/v1/staff", Map.of("name", "Helper", "phone", TestAccounts.newPhone(),
                "userId", TestAccounts.newUserId(), "role", "STAFF")).expectStatus(201).json("$.member.userId");
        staff.client.patch("/api/v1/staff/" + added, Map.of("role", "MANAGER")).expectError(403, "staff.role_above_yours");
        staff.client.delete("/api/v1/staff/" + manager.id).expectError(403, "staff.role_above_yours");
        staff.client.post("/api/v1/staff/" + manager.id + "/new-password", Map.of())
                .expectError(403, "staff.role_above_yours");
        staff.client.delete("/api/v1/staff/" + added).expectStatus(204);
    }

    private ApiClient owner(String business) {
        ApiClient client = new ApiClient(port);
        TestAccounts.signUp(client, messages);
        TestBusinesses.create(client, business);
        return client;
    }

    private record Member(ApiClient client, String userId, UUID id) {
    }

    /** Added by the owner, signed in, with their own password set. */
    private Member member(ApiClient owner, String role) {
        String userId = TestAccounts.newUserId();
        ApiResponse added = owner.post("/api/v1/staff", Map.of("name", "Member", "phone", TestAccounts.newPhone(),
                "userId", userId, "role", role)).expectStatus(201);
        ApiClient client = new ApiClient(port);
        client.post("/api/v1/auth/login", Map.of("userId", userId, "password", added.json("$.startingPassword")))
                .expectStatus(200);
        client.post("/api/v1/me/first-password", Map.of("newPassword", OWN_PASSWORD)).expectStatus(200);
        return new Member(client, userId, UUID.fromString(added.json("$.member.userId")));
    }
}
