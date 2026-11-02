package com.mojud.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAccounts.Account;
import com.mojud.support.TestBusinesses;
import com.mojud.tenancy.membership.MembershipRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/** Businesses and the business each request works in (implementation-plan.md 2a). */
@IntegrationTest
class BusinessTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    SystemDatabase systemDatabase;

    @Test
    void creatingABusinessMakesTheCallerOwnerAndSelectsIt() {
        ApiClient client = signedIn();

        ApiResponse created = client.post("/api/v1/businesses",
                Map.of("name", "Rahima's Kurti House", "phone", "01712-345678", "address", "Mirpur 10, Dhaka"))
                .expectStatus(201);

        assertThat(created.<String>json("$.role")).isEqualTo("OWNER");
        assertThat(created.<String>json("$.phone")).isEqualTo("+8801712345678");
        ApiResponse current = client.get("/api/v1/business").expectStatus(200);
        assertThat(current.<String>json("$.name")).isEqualTo("Rahima's Kurti House");
        assertThat(client.get("/api/v1/business/settings").expectStatus(200).<String>json("$.stockCommitPoint"))
                .isEqualTo("SHIP");
    }

    @Test
    void aUserInTwoBusinessesSeesEachOnlyAfterSwitching() {
        ApiClient client = signedIn();
        UUID first = TestBusinesses.create(client, "First Shop");
        UUID second = joinSomeoneElses(client, "Second Shop", BusinessRole.MANAGER);
        client.post("/api/v1/me/switch-business", Map.of("businessId", second.toString())).expectStatus(200);

        assertThat(client.get("/api/v1/business").<String>json("$.name")).isEqualTo("Second Shop");
        client.patch("/api/v1/business/settings", Map.of("stockCommitPoint", "CONFIRM", "lowStockDefault", 12))
                .expectStatus(200);

        client.post("/api/v1/me/switch-business", Map.of("businessId", first.toString())).expectStatus(200);

        assertThat(client.get("/api/v1/business").<String>json("$.name")).isEqualTo("First Shop");
        ApiResponse settings = client.get("/api/v1/business/settings").expectStatus(200);
        assertThat(settings.<String>json("$.stockCommitPoint")).isEqualTo("SHIP");
        assertThat(settings.<Integer>json("$.lowStockDefault")).isEqualTo(5);

        List<Map<String, Object>> mine = client.get("/api/v1/businesses").expectStatus(200).json("$");
        assertThat(mine).extracting(b -> b.get("name")).containsExactly("First Shop", "Second Shop");
        assertThat(mine).extracting(b -> b.get("selected")).containsExactly(true, false);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void nobodyCanSwitchToABusinessTheyDoNotBelongTo() {
        UUID someoneElses = TestBusinesses.create(signedIn(), "Not Yours");
        ApiClient client = signedIn();
        TestBusinesses.create(client, "Mine");

        client.post("/api/v1/me/switch-business", Map.of("businessId", someoneElses.toString()))
                .expectError(404, "resource.not_found");
        client.post("/api/v1/me/switch-business", Map.of("businessId", UUID.randomUUID().toString()))
                .expectError(404, "resource.not_found");

        assertThat(client.get("/api/v1/business").<String>json("$.name")).isEqualTo("Mine");
    }

    @Test
    void withoutABusinessBusinessEndpointsSaySoAndTheListIsEmpty() {
        ApiClient client = signedIn();

        client.get("/api/v1/business").expectError(409, "business.not_selected");
        assertThat(client.get("/api/v1/businesses").expectStatus(200).<List<Object>>json("$")).isEmpty();
    }

    @Test
    void afterSignInTheBusinessUsedLastIsSelected() {
        ApiClient client = signedIn();
        Account account = accountOf(client);
        UUID first = TestBusinesses.create(client, "Used Last");
        joinSomeoneElses(client, "Joined Last", BusinessRole.STAFF);
        client.post("/api/v1/me/switch-business", Map.of("businessId", first.toString())).expectStatus(200);

        ApiClient fresh = new ApiClient(port);
        fresh.post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password()))
                .expectStatus(200);

        assertThat(fresh.get("/api/v1/business").expectStatus(200).<String>json("$.name")).isEqualTo("Used Last");
    }

    @Test
    void rolesDecideWhatAMemberMayChange() {
        UUID business = TestBusinesses.create(signedIn(), "Team Shop");
        ApiClient staff = signedIn();
        ApiClient manager = signedIn();
        TestBusinesses.addMember(memberships, transactions, business, TestBusinesses.userId(staff), BusinessRole.STAFF);
        TestBusinesses.addMember(memberships, transactions, business, TestBusinesses.userId(manager), BusinessRole.MANAGER);

        assertThat(staff.get("/api/v1/business").expectStatus(200).<String>json("$.role")).isEqualTo("STAFF");
        staff.get("/api/v1/business/settings").expectStatus(200);
        staff.patch("/api/v1/business/settings", Map.of("lowStockDefault", 1)).expectError(403, "access.denied");
        staff.patch("/api/v1/business", Map.of("name", "Hacked")).expectError(403, "access.denied");

        manager.patch("/api/v1/business/settings", Map.of("lowStockDefault", 7)).expectStatus(200);
        manager.patch("/api/v1/business", Map.of("address", "Uttara, Dhaka")).expectStatus(200);
        assertThat(staff.get("/api/v1/business/settings").<Integer>json("$.lowStockDefault")).isEqualTo(7);
    }

    @Test
    void onlyTheOwnerChoosesTheSearchColoursEveryoneSees() {
        ApiClient owner = signedIn();
        UUID business = TestBusinesses.create(owner, "Colour Shop");
        ApiClient manager = signedIn();
        ApiClient staff = signedIn();
        TestBusinesses.addMember(memberships, transactions, business, TestBusinesses.userId(manager), BusinessRole.MANAGER);
        TestBusinesses.addMember(memberships, transactions, business, TestBusinesses.userId(staff), BusinessRole.STAFF);
        assertThat(staff.get("/api/v1/business/settings").<String>json("$.searchTheme")).isEqualTo("LEDGER");

        manager.put("/api/v1/business/search-theme", Map.of("searchTheme", "MINT")).expectError(403, "access.denied");
        owner.put("/api/v1/business/search-theme", Map.of("searchTheme", "PURPLE")).expectStatus(400);
        assertThat(owner.put("/api/v1/business/search-theme", Map.of("searchTheme", "MINT")).expectStatus(200)
                .<String>json("$.searchTheme")).isEqualTo("MINT");

        assertThat(staff.get("/api/v1/business/settings").<String>json("$.searchTheme")).isEqualTo("MINT");
        // Other settings leave it alone.
        manager.patch("/api/v1/business/settings", Map.of("lowStockDefault", 3)).expectStatus(200);
        assertThat(staff.get("/api/v1/business/settings").<String>json("$.searchTheme")).isEqualTo("MINT");
    }

    @Test
    void aRemovedMemberLosesTheBusinessOnTheNextRequest() {
        UUID business = TestBusinesses.create(signedIn(), "Strict Shop");
        ApiClient staff = signedIn();
        UUID staffId = TestBusinesses.userId(staff);
        TestBusinesses.addMember(memberships, transactions, business, staffId, BusinessRole.STAFF);
        staff.get("/api/v1/business").expectStatus(200);

        systemDatabase.jdbc().sql("update memberships set status = 'REMOVED' where business_id = ? and user_id = ?")
                .params(business, staffId).update();

        staff.get("/api/v1/business").expectError(409, "business.not_selected");
        assertThat(staff.get("/api/v1/businesses").<List<Object>>json("$")).isEmpty();
    }

    @Test
    void aRoleChangeCountsFromTheNextRequest() {
        UUID business = TestBusinesses.create(signedIn(), "Promoting Shop");
        ApiClient member = signedIn();
        UUID memberId = TestBusinesses.userId(member);
        TestBusinesses.addMember(memberships, transactions, business, memberId, BusinessRole.STAFF);
        member.patch("/api/v1/business/settings", Map.of("lowStockDefault", 2)).expectError(403, "access.denied");

        systemDatabase.jdbc().sql("update memberships set role = 'MANAGER' where business_id = ? and user_id = ?")
                .params(business, memberId).update();

        member.patch("/api/v1/business/settings", Map.of("lowStockDefault", 2)).expectStatus(200);
    }

    @Test
    void businessFieldsAreValidated() {
        ApiClient client = signedIn();
        client.post("/api/v1/businesses", Map.of("name", " ")).expectError(400, "validation.failed");
        client.post("/api/v1/businesses", Map.of("name", "Shop", "phone", "12345")).expectError(400, "validation.failed");
        TestBusinesses.create(client, "Valid Shop");

        ApiResponse settings = client.patch("/api/v1/business/settings",
                Map.of("stockCommitPoint", "LATER", "lowStockDefault", -1)).expectError(400, "validation.failed");
        assertThat(settings.<List<String>>json("$.errors[*].field")).contains("stockCommitPoint");
        ApiResponse prefix = client.patch("/api/v1/business/settings", Map.of("lowStockDefault", -1, "orderNumberPrefix", "ab c"))
                .expectError(400, "validation.failed");
        assertThat(prefix.<List<String>>json("$.errors[*].field")).containsExactlyInAnyOrder("lowStockDefault", "orderNumberPrefix");
        client.patch("/api/v1/business", Map.of("name", " ")).expectError(400, "validation.failed");
    }

    @Test
    void anEmptyPhoneOrAddressClearsIt() {
        ApiClient client = signedIn();
        client.post("/api/v1/businesses", Map.of("name", "Shop", "phone", "01712345678", "address", "Dhaka"))
                .expectStatus(201);

        ApiResponse updated = client.patch("/api/v1/business", Map.of("phone", "", "address", "")).expectStatus(200);

        assertThat(updated.<String>json("$.phone")).isNull();
        assertThat(updated.<String>json("$.address")).isNull();
        assertThat(updated.<String>json("$.name")).isEqualTo("Shop");
    }

    @Test
    void oneAccountCanOwnOnlyOneBusiness() {
        ApiClient client = signedIn();
        TestBusinesses.create(client, "Shop 1");

        client.post("/api/v1/businesses", Map.of("name", "Shop 2")).expectError(409, "business.limit_reached");
        assertThat(client.get("/api/v1/businesses").<List<Object>>json("$")).hasSize(1);
    }

    @Test
    void workingInSomeoneElsesBusinessDoesNotStopOwningOne() {
        ApiClient client = signedIn();
        joinSomeoneElses(client, "Employer's Shop", BusinessRole.STAFF);

        TestBusinesses.create(client, "My Own Shop");

        assertThat(client.get("/api/v1/businesses").<List<Object>>json("$")).hasSize(2);
    }

    @Test
    void aSuspendedBusinessStillCountsAsTheOneOwned() {
        ApiClient client = signedIn();
        UUID owned = TestBusinesses.create(client, "Suspended Shop");
        systemDatabase.jdbc().sql("update businesses set status = 'SUSPENDED' where id = ?").param(owned).update();

        client.post("/api/v1/businesses", Map.of("name", "Fresh Start")).expectError(409, "business.limit_reached");
    }

    @Test
    void businessEndpointsNeedASession() {
        new ApiClient(port).get("/api/v1/business").expectError(401, "auth.required");
        new ApiClient(port).post("/api/v1/businesses", Map.of("name", "x")).expectError(401, "auth.required");
    }

    // Fixes from the 2a code review.

    @Test
    void parallelCreatesCannotPassTheOwnerLimitTogether() throws Exception {
        ApiClient client = signedIn();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<ApiResponse>> answers = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String name = "Racing " + i;
                answers.add(pool.submit(() -> client.post("/api/v1/businesses", Map.of("name", name))));
            }
            int created = 0;
            for (java.util.concurrent.Future<ApiResponse> answer : answers) {
                ApiResponse response = answer.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (response.status() == 201) {
                    created++;
                } else {
                    response.expectError(409, "business.limit_reached");
                }
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(client.get("/api/v1/businesses").<List<Object>>json("$")).hasSize(1);
    }

    @Test
    void aSuspendedBusinessCannotBeUsedOrSwitchedTo() {
        ApiClient client = signedIn();
        UUID other = TestBusinesses.create(client, "Other Shop");
        UUID suspended = joinSomeoneElses(client, "Suspended Shop", BusinessRole.STAFF);
        client.post("/api/v1/me/switch-business", Map.of("businessId", suspended.toString())).expectStatus(200);

        systemDatabase.jdbc().sql("update businesses set status = 'SUSPENDED' where id = ?").param(suspended).update();

        assertThat(client.get("/api/v1/business").expectStatus(200).<String>json("$.id")).isEqualTo(other.toString());
        client.post("/api/v1/me/switch-business", Map.of("businessId", suspended.toString()))
                .expectError(404, "resource.not_found");
    }

    @Test
    void someoneRemovedFromTheSelectedBusinessLandsInTheirOtherOneAtOnce() {
        UUID shared = TestBusinesses.create(signedIn(), "Shared Shop");
        ApiClient member = signedIn();
        UUID own = TestBusinesses.create(member, "Own Shop");
        TestBusinesses.addMember(memberships, transactions, shared, TestBusinesses.userId(member), BusinessRole.STAFF);
        member.post("/api/v1/me/switch-business", Map.of("businessId", shared.toString())).expectStatus(200);

        systemDatabase.jdbc().sql("update memberships set status = 'REMOVED' where business_id = ? and user_id = ?")
                .params(shared, TestBusinesses.userId(member)).update();

        assertThat(member.get("/api/v1/business").expectStatus(200).<String>json("$.id")).isEqualTo(own.toString());
    }

    @Test
    void switchingDoesNotCountAsEditingTheMembership() {
        ApiClient client = signedIn();
        UUID first = TestBusinesses.create(client, "First");
        UUID second = joinSomeoneElses(client, "Second", BusinessRole.STAFF);
        client.post("/api/v1/me/switch-business", Map.of("businessId", second.toString())).expectStatus(200);
        UUID userId = TestBusinesses.userId(client);
        Long before = membershipVersion(first, userId);

        client.post("/api/v1/me/switch-business", Map.of("businessId", first.toString())).expectStatus(200);

        assertThat(membershipVersion(first, userId)).isEqualTo(before);
        assertThat(client.get("/api/v1/businesses").<List<String>>json("$[*].name")).first().isEqualTo("First");
    }

    @Test
    void signingInOnTheSameBrowserStartsFresh() {
        ApiClient browser = signedIn();
        UUID shared = TestBusinesses.create(browser, "Counter Shop");
        ApiClient second = signedIn();
        Account secondAccount = accountOf(second);
        UUID secondsOwn = TestBusinesses.create(second, "Second's Shop");
        TestBusinesses.addMember(memberships, transactions, shared, TestBusinesses.userId(second), BusinessRole.STAFF);
        systemDatabase.jdbc().sql("update memberships set last_used_at = now() - interval '1 day' where business_id = ? and user_id = ?")
                .params(shared, TestBusinesses.userId(second)).update();
        browser.get("/api/v1/business").expectStatus(200);

        // The second user signs in on the first user's browser without signing them out.
        browser.post("/api/v1/auth/login", Map.of("userId", secondAccount.userId(), "password", secondAccount.password()))
                .expectStatus(200);

        assertThat(browser.get("/api/v1/business").expectStatus(200).<String>json("$.id")).isEqualTo(secondsOwn.toString());
    }

    private Long membershipVersion(UUID businessId, UUID userId) {
        return systemDatabase.jdbc().sql("select version from memberships where business_id = ? and user_id = ?")
                .params(businessId, userId).query(Long.class).single();
    }

    /** Someone else's new business, with {@code client} added as a member. */
    private UUID joinSomeoneElses(ApiClient client, String name, BusinessRole role) {
        UUID business = TestBusinesses.create(signedIn(), name);
        TestBusinesses.addMember(memberships, transactions, business, TestBusinesses.userId(client), role);
        return business;
    }

    private final Map<ApiClient, Account> accounts = new java.util.IdentityHashMap<>();

    private ApiClient signedIn() {
        ApiClient client = new ApiClient(port);
        accounts.put(client, TestAccounts.signUp(client, messages));
        return client;
    }

    private Account accountOf(ApiClient client) {
        return accounts.get(client);
    }
}
