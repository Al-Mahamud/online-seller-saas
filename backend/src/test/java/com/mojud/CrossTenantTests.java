package com.mojud;

import static com.mojud.support.CrossTenant.expectNoLeak;
import static com.mojud.support.CrossTenant.expectNotFound;
import static com.mojud.support.CrossTenant.expectRefused;
import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.CrossTenant;
import com.mojud.support.CrossTenant.Tenant;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The cross-tenant suite (backend-plan.md §4): business B's owner attacks every endpoint with
 * business A's ids. B's owner holds the strongest role in B, so a role check can never hide a
 * missing business check.
 *
 * <p>Every endpoint must have a case below. A new endpoint without one fails
 * {@link #everyEndpointHasACase}, so it cannot ship without saying how it keeps businesses apart.
 */
@IntegrationTest
class CrossTenantTests {

    /** How business B attacks one endpoint. */
    @FunctionalInterface
    interface Attack {

        void run(Tenant a, Tenant b);
    }

    /** For endpoints that hold no business data; the reason is the review record. */
    static Attack notBusinessData(String reason) {
        return (a, b) -> {
        };
    }

    private static final Map<String, Attack> CASES = new TreeMap<>();

    static {
        // Signing up and in: no business is involved yet.
        String auth = "signing up and in; nothing belongs to a business";
        CASES.put("GET /api/v1/auth/options", notBusinessData(auth));
        CASES.put("GET /api/v1/auth/csrf", notBusinessData(auth));
        CASES.put("POST /api/v1/auth/otp", notBusinessData(auth));
        CASES.put("POST /api/v1/auth/signup", notBusinessData(auth));
        CASES.put("POST /api/v1/auth/login", notBusinessData(auth));
        CASES.put("POST /api/v1/auth/password/reset", notBusinessData(auth));

        // The caller's own account.
        String ownAccount = "acts on the signed-in user's own account only";
        CASES.put("GET /api/v1/me", (a, b) -> expectNoLeak(b.owner().get("/api/v1/me"), a));
        CASES.put("PATCH /api/v1/me", (a, b) -> expectNoLeak(b.owner().patch("/api/v1/me", Map.of()), a));
        CASES.put("POST /api/v1/me/password", notBusinessData(ownAccount));
        CASES.put("POST /api/v1/me/contact/code", notBusinessData(ownAccount));
        CASES.put("POST /api/v1/me/contact", notBusinessData(ownAccount));

        // Businesses.
        CASES.put("POST /api/v1/businesses", notBusinessData("creates a new business owned by the caller"));
        CASES.put("GET /api/v1/businesses", (a, b) -> expectNoLeak(b.owner().get("/api/v1/businesses"), a));
        CASES.put("POST /api/v1/me/switch-business", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/me/switch-business", Map.of("businessId", a.businessId().toString()))));
        CASES.put("GET /api/v1/business", (a, b) -> expectNoLeak(b.owner().get("/api/v1/business"), a));
        CASES.put("PATCH /api/v1/business", (a, b) -> expectNoLeak(b.owner().patch("/api/v1/business", Map.of()), a));
        CASES.put("GET /api/v1/business/settings", (a, b) -> expectNoLeak(b.owner().get("/api/v1/business/settings"), a));
        CASES.put("PATCH /api/v1/business/settings",
                (a, b) -> expectNoLeak(b.owner().patch("/api/v1/business/settings", Map.of()), a));
        CASES.put("GET /api/v1/business/audit-log", (a, b) -> expectNoLeak(b.owner().get("/api/v1/business/audit-log"), a));

        // Staff.
        CASES.put("GET /api/v1/staff", (a, b) -> expectNoLeak(b.owner().get("/api/v1/staff"), a));
        CASES.put("PATCH /api/v1/staff/{userId}", (a, b) -> expectNotFound(
                b.owner().patch("/api/v1/staff/" + a.staffId(), Map.of("role", "MANAGER"))));
        CASES.put("DELETE /api/v1/staff/{userId}", (a, b) -> expectNotFound(
                b.owner().delete("/api/v1/staff/" + a.staffId())));
        CASES.put("POST /api/v1/staff/{userId}/make-owner", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/staff/" + a.staffId() + "/make-owner",
                        Map.of("currentPassword", b.ownerAccount().password()))));
        CASES.put("GET /api/v1/staff/invites", (a, b) -> expectNoLeak(b.owner().get("/api/v1/staff/invites"), a));
        CASES.put("POST /api/v1/staff/invites", (a, b) -> expectNoLeak(
                b.owner().post("/api/v1/staff/invites", Map.of("to", TestAccounts.newEmail(), "role", "STAFF")), a));
        CASES.put("DELETE /api/v1/staff/invites/{inviteId}", (a, b) -> expectNotFound(
                b.owner().delete("/api/v1/staff/invites/" + a.inviteId())));

        // Invites, from the invitee's side.
        CASES.put("POST /api/v1/public/invites/lookup",
                notBusinessData("the token is the secret: whoever holds the link may see the invite"));
        CASES.put("POST /api/v1/invites/accept", (a, b) -> expectRefused(
                b.owner().post("/api/v1/invites/accept", Map.of("token", a.inviteToken())), a));

        // Categories and tags.
        CASES.put("GET /api/v1/categories", (a, b) -> expectNoLeak(b.owner().get("/api/v1/categories"), a));
        CASES.put("POST /api/v1/categories", (a, b) -> expectNotFound(b.owner().post("/api/v1/categories",
                Map.of("name", "Sneaky", "parentId", a.categoryId().toString()))));
        CASES.put("PATCH /api/v1/categories/{id}", (a, b) -> expectNotFound(
                b.owner().patch("/api/v1/categories/" + a.subcategoryId(), Map.of("name", "Renamed"))));
        CASES.put("POST /api/v1/categories/{id}/move", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/categories/" + a.subcategoryId() + "/move", Map.of()));
            expectNotFound(b.owner().post("/api/v1/categories/" + b.subcategoryId() + "/move",
                    Map.of("parentId", a.categoryId().toString())));
        });
        CASES.put("PUT /api/v1/categories/order", (a, b) -> {
            expectNotFound(b.owner().put("/api/v1/categories/order",
                    Map.of("parentId", a.categoryId().toString(), "ids", List.of(a.subcategoryId().toString()))));
            expectRefused(b.owner().put("/api/v1/categories/order",
                    Map.of("ids", List.of(a.categoryId().toString(), b.categoryId().toString()))), a);
        });
        CASES.put("DELETE /api/v1/categories/{id}", (a, b) -> expectNotFound(
                b.owner().delete("/api/v1/categories/" + a.subcategoryId())));
        CASES.put("GET /api/v1/tags", (a, b) -> expectNoLeak(b.owner().get("/api/v1/tags"), a));
        CASES.put("POST /api/v1/tags", (a, b) -> expectNoLeak(
                b.owner().post("/api/v1/tags", Map.of("name", "fresh-" + UUID.randomUUID())), a));
        CASES.put("PATCH /api/v1/tags/{id}", (a, b) -> expectNotFound(
                b.owner().patch("/api/v1/tags/" + a.tagId(), Map.of("name", "renamed"))));
        CASES.put("DELETE /api/v1/tags/{id}", (a, b) -> expectNotFound(b.owner().delete("/api/v1/tags/" + a.tagId())));

        // Test sources only.
        CASES.put("GET /dev/otp", notBusinessData("exists only in test sources and local runs"));
    }

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    Tenant a;
    Tenant b;

    @BeforeEach
    void twoBusinesses() {
        a = CrossTenant.setUp(port, messages, this::membershipId);
        b = CrossTenant.setUp(port, messages, this::membershipId);
    }

    @Test
    void everyEndpointHasACase() {
        CrossTenant.assertEveryEndpointHasACase(CrossTenant.endpoints(mapping), CASES.keySet());
    }

    @Test
    void noEndpointLetsOneBusinessReachAnother() {
        List<String> failures = new ArrayList<>();
        CASES.forEach((endpoint, attack) -> {
            try {
                attack.run(a, b);
            } catch (AssertionError e) {
                failures.add(endpoint + ": " + e.getMessage());
            }
        });

        assertThat(failures).as("cross-tenant failures").isEmpty();
        businessAIsUnchanged();
    }

    /** After every attack, A still looks exactly as it did. */
    private void businessAIsUnchanged() {
        assertThat(a.owner().get("/api/v1/business").expectStatus(200).<String>json("$.name")).isEqualTo(a.name());
        assertThat(a.owner().get("/api/v1/business/settings").<String>json("$.orderNumberPrefix")).isEqualTo(a.orderPrefix());
        assertThat(a.owner().get("/api/v1/staff").<List<String>>json("$[*].role")).containsExactly("OWNER", "STAFF");
        assertThat(a.owner().get("/api/v1/staff/invites").<List<String>>json("$[*].id")).containsExactly(a.inviteId().toString());
        a.staff().get("/api/v1/business").expectStatus(200);
        ApiResponse tree = a.owner().get("/api/v1/categories").expectStatus(200);
        assertThat(tree.<List<String>>json("$[*].name")).containsExactly(a.categoryName());
        assertThat(tree.<List<String>>json("$[0].children[*].name")).containsExactly(a.subcategoryName());
        assertThat(a.owner().get("/api/v1/tags").<List<String>>json("$[*].name")).containsExactly(a.tagName());
    }

    private UUID membershipId(UUID businessId, UUID userId) {
        return systemDatabase.jdbc().sql("select id from memberships where business_id = ? and user_id = ?")
                .params(businessId, userId).query(UUID.class).single();
    }
}
