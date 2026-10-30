package com.mojud.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestClock;
import com.mojud.support.TestAdmins;
import com.mojud.support.TestBusinesses;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Platform administration (implementation-plan.md 12a). */
@IntegrationTest
class AdminTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    TestClock testClock;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    ApiClient owner;
    TestAccounts.Account ownerAccount;
    UUID business;
    ApiClient admin;

    @BeforeEach
    void setUp() {
        owner = new ApiClient(port);
        ownerAccount = TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Admin Test Shop " + UUID.randomUUID().toString().substring(0, 6));
        admin = admin();
    }

    @Test
    void everyAdminEndpointRefusesEveryoneElse() {
        List<String> routes = new ArrayList<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(pattern -> pattern.startsWith("/api/v1/admin/"))
                .forEach(pattern -> info.getMethodsCondition().getMethods()
                        .forEach(method -> routes.add(method + " " + pattern))));
        assertThat(routes).hasSizeGreaterThanOrEqualTo(12);

        ApiClient anonymous = new ApiClient(port);
        for (String route : routes) {
            String method = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1).replaceAll("\\{code}", "PRO").replaceAll("\\{[^}]+}",
                    UUID.randomUUID().toString());
            assertThat(call(owner, method, path).status()).as("owner: " + route).isEqualTo(403);
            assertThat(call(anonymous, method, path).status()).as("signed out: " + route).isEqualTo(401);
        }
    }

    @Test
    void businessesAreFoundByNameOrOwner() {
        String name = owner.get("/api/v1/business").json("$.name");

        assertThat(ids(admin.get("/api/v1/admin/businesses?q=" + name.substring(16).replace(" ", "%20")))).contains(business.toString());
        assertThat(ids(admin.get("/api/v1/admin/businesses?q=" + ownerAccount.email()))).containsExactly(business.toString());
        assertThat(ids(admin.get("/api/v1/admin/businesses?q=nothing-like-this"))).isEmpty();

        ApiResponse row = admin.get("/api/v1/admin/businesses?q=" + ownerAccount.email());
        assertThat(row.<String>json("$[0].business.owner.email")).isEqualTo(ownerAccount.email());
        assertThat(row.<String>json("$[0].subscription.status")).isEqualTo("TRIALING");

        ApiResponse detail = admin.get("/api/v1/admin/businesses/" + business).expectStatus(200);
        assertThat(detail.<Integer>json("$.business.members")).isEqualTo(1);
        assertThat(detail.<String>json("$.billing.planCode")).isEqualTo("TRIAL");
        assertThat(detail.<Integer>json("$.billing.usage.variants.limit")).isEqualTo(5000);
        admin.get("/api/v1/admin/businesses/" + UUID.randomUUID()).expectError(404, "resource.not_found");
    }

    @Test
    void suspendingLeavesOnlyBillingAndIsUndone() {
        admin.post("/api/v1/admin/businesses/" + business + "/suspend", Map.of("reason", "Chargeback investigation"))
                .expectStatus(200);
        owner.get("/api/v1/products").expectError(402, "subscription.suspended");
        owner.get("/api/v1/billing").expectStatus(200);
        admin.post("/api/v1/admin/businesses/" + business + "/suspend", Map.of("reason", "again")).expectError(409, "admin.no_change");

        ApiResponse back = admin.post("/api/v1/admin/businesses/" + business + "/unsuspend", null).expectStatus(200);

        assertThat(back.<String>json("$.billing.status")).isEqualTo("TRIALING");
        owner.get("/api/v1/products").expectStatus(200);
        assertThat(actions()).containsSequence("business.unsuspended", "business.suspended");
        assertThat(admin.get("/api/v1/admin/audit?businessId=" + business).<String>json("$[1].details.reason"))
                .isEqualTo("Chargeback investigation");
    }

    @Test
    void trialsAreExtendedAndPlansSet() {
        Instant trialEnd = Instant.parse(owner.get("/api/v1/billing").json("$.trialEndsAt"));

        admin.post("/api/v1/admin/businesses/" + business + "/extend-trial", Map.of("days", 30)).expectStatus(200);
        assertThat(Instant.parse(owner.get("/api/v1/billing").json("$.trialEndsAt")))
                .isCloseTo(trialEnd.plus(Duration.ofDays(30)), within());
        admin.post("/api/v1/admin/businesses/" + business + "/extend-trial", Map.of("days", 91)).expectError(400, "validation.failed");

        Instant until = Instant.now().plus(Duration.ofDays(60)).truncatedTo(ChronoUnit.SECONDS);
        admin.put("/api/v1/admin/businesses/" + business + "/plan", Map.of("planCode", "PRO", "periodEnd", until.toString()))
                .expectStatus(200);
        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(billing.<String>json("$.plan.code")).isEqualTo("PRO");
        assertThat(Instant.parse(billing.json("$.currentPeriodEnd"))).isEqualTo(until);
        admin.post("/api/v1/admin/businesses/" + business + "/extend-trial", Map.of("days", 5)).expectError(409, "billing.not_on_trial");
        admin.put("/api/v1/admin/businesses/" + business + "/plan", Map.of("planCode", "TRIAL", "periodEnd", until.toString()))
                .expectError(400, "validation.failed");

        assertThat(actions()).containsSequence("plan.set", "trial.extended");
    }

    @Test
    void plansAreEditedAndTheChangeIsAudited() {
        ApiResponse before = admin.get("/api/v1/admin/plans").expectStatus(200);
        Map<String, Integer> limits = before.<List<Map<String, Integer>>>json("$[?(@.code == 'STARTER')].limits").get(0);
        try {
            Map<String, Integer> more = new HashMap<>(limits);
            more.put("variants", 600);
            ApiResponse edited = admin.put("/api/v1/admin/plans/STARTER", Map.of("nameEn", "Starter Plus", "priceMonthly", 120000,
                    "isPublic", true, "limits", more, "exports", true)).expectStatus(200);

            assertThat(edited.<Integer>json("$.limits.variants")).isEqualTo(600);
            ApiResponse offered = owner.get("/api/v1/plans");
            assertThat(offered.<List<String>>json("$[?(@.code == 'STARTER')].nameEn")).containsExactly("Starter Plus");
            assertThat(offered.<List<Integer>>json("$[?(@.code == 'STARTER')].priceMonthly")).containsExactly(120000);
            assertThat(admin.get("/api/v1/admin/audit").<String>json("$[0].action")).isEqualTo("plan.edited");
            assertThat(admin.get("/api/v1/admin/audit").<String>json("$[0].adminName")).isNotBlank();
            admin.put("/api/v1/admin/plans/TRIAL", Map.of("priceMonthly", 0, "isPublic", true, "exports", true)).expectStatus(200);
            assertThat(owner.get("/api/v1/plans").<List<String>>json("$[*].code")).doesNotContain("TRIAL");
            admin.put("/api/v1/admin/plans/NOPE", Map.of("priceMonthly", 1, "isPublic", true, "exports", true))
                    .expectError(404, "resource.not_found");
        } finally {
            admin.put("/api/v1/admin/plans/STARTER", Map.of("nameEn", "Starter", "priceMonthly", 99000, "isPublic", true,
                    "limits", limits, "exports", false)).expectStatus(200);
            admin.put("/api/v1/admin/plans/TRIAL", Map.of("priceMonthly", 0, "isPublic", false, "exports", true)).expectStatus(200);
        }
    }

    @Test
    void markingABankTransferPaidIsAudited() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1, "payBy", "BANK_TRANSFER"))
                .expectStatus(201).json("$.invoiceId");

        admin.post("/api/v1/admin/billing/invoices/" + invoice + "/mark-paid", Map.of("reference", "DBBL-1")).expectStatus(200);

        assertThat(actions()).first().isEqualTo("invoice.marked_paid");
    }

    private List<String> actions() {
        return admin.get("/api/v1/admin/audit?businessId=" + business).json("$[*].action");
    }

    private static List<String> ids(ApiResponse response) {
        return response.expectStatus(200).json("$[*].business.id");
    }

    private static ApiResponse call(ApiClient client, String method, String path) {
        return switch (RequestMethod.valueOf(method)) {
            case GET -> client.get(path);
            case POST -> client.post(path, Map.of());
            case PUT -> client.put(path, Map.of());
            case PATCH -> client.patch(path, Map.of());
            case DELETE -> client.delete(path);
            default -> throw new IllegalStateException(method);
        };
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return org.assertj.core.api.Assertions.within(5, ChronoUnit.MINUTES);
    }

    private ApiClient admin() {
        return TestAdmins.signIn(port, messages, systemDatabase, testClock);
    }
}
