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
import com.mojud.support.TestImages;
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
            expectNotFound(b.owner().post("/api/v1/categories/" + a.subcategoryId() + "/move", Map.of("topLevel", true)));
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

        // Products and variants.
        CASES.put("GET /api/v1/products", (a, b) -> {
            expectNoLeak(b.owner().get("/api/v1/products"), a);
            expectNoLeak(b.owner().get("/api/v1/products?categoryId=" + a.subcategoryId()), a);
            expectNoLeak(b.owner().get("/api/v1/products?tagId=" + a.tagId()), a);
            expectNoLeak(b.owner().get("/api/v1/products?q=" + a.productName().replace(' ', '+')), a);
            expectNoLeak(b.owner().get("/api/v1/products?q=" + a.sku()), a);
        });
        CASES.put("GET /api/v1/products/{id}", (a, b) -> expectNotFound(b.owner().get("/api/v1/products/" + a.productId())));
        CASES.put("POST /api/v1/products", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/products",
                    Map.of("name", "Sneaky", "price", 100, "categoryId", a.categoryId().toString())));
            expectNotFound(b.owner().post("/api/v1/products",
                    Map.of("name", "Sneaky", "price", 100, "tagIds", List.of(a.tagId().toString()))));
            // A's SKU is free in B: SKUs are unique per business, and A's existence must not show.
            b.owner().post("/api/v1/products", Map.of("name", "Same SKU", "price", 100, "sku", a.sku())).expectStatus(201);
        });
        CASES.put("PATCH /api/v1/products/{id}", (a, b) -> {
            expectNotFound(b.owner().patch("/api/v1/products/" + a.productId(), Map.of("name", "Renamed")));
            expectNotFound(b.owner().patch("/api/v1/products/" + b.productId(), Map.of("categoryId", a.categoryId().toString())));
            expectNotFound(b.owner().patch("/api/v1/products/" + b.productId(), Map.of("tagIds", List.of(a.tagId().toString()))));
        });
        CASES.put("POST /api/v1/products/{id}/archive", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/products/" + a.productId() + "/archive", null)));
        CASES.put("POST /api/v1/products/{id}/restore", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/products/" + a.productId() + "/restore", null)));
        CASES.put("PUT /api/v1/products/{id}/options", (a, b) -> expectNotFound(
                b.owner().put("/api/v1/products/" + a.productId() + "/options", Map.of("options", List.of()))));
        CASES.put("PATCH /api/v1/variants/{id}", (a, b) -> expectNotFound(
                b.owner().patch("/api/v1/variants/" + a.variantId(), Map.of("price", 1))));
        CASES.put("POST /api/v1/variants/{id}/archive", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/variants/" + a.variantId() + "/archive", null)));
        CASES.put("POST /api/v1/variants/{id}/restore", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/variants/" + a.variantId() + "/restore", null)));

        // Files, product images and the logo.
        CASES.put("POST /api/v1/uploads", (a, b) -> expectNoLeak(
                b.owner().upload("/api/v1/uploads", "x.png", TestImages.png(300, 300)), a));
        CASES.put("GET /api/v1/files/{id}/{size}", (a, b) -> {
            org.assertj.core.api.Assertions.assertThat(
                    b.owner().download("/api/v1/files/" + a.fileId() + "/large").statusCode()).isEqualTo(404);
            org.assertj.core.api.Assertions.assertThat(
                    b.owner().download("/api/v1/files/" + a.fileId() + "/small").statusCode()).isEqualTo(404);
        });
        CASES.put("POST /api/v1/products/{id}/images", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/products/" + a.productId() + "/images", Map.of("fileId", b.fileId().toString())));
            expectNotFound(b.owner().post("/api/v1/products/" + b.productId() + "/images", Map.of("fileId", a.fileId().toString())));
            expectNotFound(b.owner().post("/api/v1/products/" + b.productId() + "/images",
                    Map.of("fileId", b.fileId().toString(), "variantId", a.variantId().toString())));
        });
        CASES.put("PATCH /api/v1/products/{id}/images/{imageId}", (a, b) -> {
            expectNotFound(b.owner().patch("/api/v1/products/" + a.productId() + "/images/" + a.productImageId(), Map.of()));
            expectNotFound(b.owner().patch("/api/v1/products/" + b.productId() + "/images/" + a.productImageId(), Map.of()));
        });
        CASES.put("PUT /api/v1/products/{id}/images/order", (a, b) -> expectNotFound(
                b.owner().put("/api/v1/products/" + a.productId() + "/images/order", Map.of("ids", List.of(a.productImageId().toString())))));
        CASES.put("DELETE /api/v1/products/{id}/images/{imageId}", (a, b) -> {
            expectNotFound(b.owner().delete("/api/v1/products/" + a.productId() + "/images/" + a.productImageId()));
            expectNotFound(b.owner().delete("/api/v1/products/" + b.productId() + "/images/" + a.productImageId()));
        });
        CASES.put("PUT /api/v1/business/logo", (a, b) -> expectNotFound(
                b.owner().put("/api/v1/business/logo", Map.of("fileId", a.fileId().toString()))));
        CASES.put("DELETE /api/v1/business/logo", (a, b) -> {
            expectNoLeak(b.owner().delete("/api/v1/business/logo"), a);
            b.owner().put("/api/v1/business/logo", Map.of("fileId", b.fileId().toString())).expectStatus(200);
        });

        // Stock.
        CASES.put("POST /api/v1/stock/adjustments", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/stock/adjustments", Map.of("lines",
                    List.of(Map.of("variantId", a.variantId().toString(), "kind", "RECEIVE", "quantity", 1)))));
            expectNotFound(b.owner().post("/api/v1/stock/adjustments", Map.of("lines",
                    List.of(Map.of("variantId", a.variantId().toString(), "kind", "COUNT", "quantity", 0)))));
            // A's idempotency key means nothing in B: B's request runs as new.
            String marker = a.name().substring("Shop ".length());
            b.owner().post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", b.variantId().toString(),
                    "kind", "RECEIVE", "quantity", 1))), Map.of("Idempotency-Key", "fixture-" + marker)).expectStatus(201);
        });
        CASES.put("GET /api/v1/variants/{id}/movements", (a, b) -> expectNotFound(
                b.owner().get("/api/v1/variants/" + a.variantId() + "/movements")));

        // Low stock and notifications.
        CASES.put("GET /api/v1/stock/low", (a, b) -> expectNoLeak(b.owner().get("/api/v1/stock/low"), a));
        CASES.put("GET /api/v1/notifications", (a, b) -> expectNoLeak(b.owner().get("/api/v1/notifications"), a));
        CASES.put("POST /api/v1/notifications/read", (a, b) -> {
            b.owner().post("/api/v1/notifications/read", Map.of("ids", List.of(a.notificationId().toString())))
                    .expectStatus(204);
            b.owner().post("/api/v1/notifications/read", Map.of("all", true)).expectStatus(204);
        });

        // Imports.
        CASES.put("POST /api/v1/imports", (a, b) -> expectNoLeak(b.owner().upload("/api/v1/imports", "b.csv",
                "Name,Price\nB item,5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)).expectStatus(201), a));
        CASES.put("GET /api/v1/imports/{id}", (a, b) -> expectNotFound(b.owner().get("/api/v1/imports/" + a.importId())));
        CASES.put("PUT /api/v1/imports/{id}/mapping", (a, b) -> expectNotFound(b.owner().put(
                "/api/v1/imports/" + a.importId() + "/mapping", Map.of("columns", List.of(
                        Map.of("index", 0, "field", "NAME"), Map.of("index", 1, "field", "PRICE"))))));
        CASES.put("GET /api/v1/imports/{id}/preview", (a, b) -> expectNotFound(
                b.owner().get("/api/v1/imports/" + a.importId() + "/preview")));
        CASES.put("POST /api/v1/imports/{id}/commit", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/imports/" + a.importId() + "/commit", null));
            assertThat(a.owner().get("/api/v1/imports/" + a.importId()).<String>json("$.status")).isEqualTo("MAPPING");
        });
        CASES.put("GET /api/v1/imports/{id}/errors.csv", (a, b) -> assertThat(
                b.owner().download("/api/v1/imports/" + a.importId() + "/errors.csv").statusCode()).isEqualTo(404));
        // Customers. Phone numbers are unique per business: B may have A's customer's number too.
        CASES.put("GET /api/v1/customers", (a, b) -> {
            expectNoLeak(b.owner().get("/api/v1/customers"), a);
            expectNoLeak(b.owner().get("/api/v1/customers?q=" + a.customerName().replace(" ", "%20")), a);
            expectNoLeak(b.owner().get("/api/v1/customers?q=" + a.customerPhone().substring(4)), a);
            expectNoLeak(b.owner().get("/api/v1/customers?phone=" + a.customerPhone().replace("+", "%2B")), a);
        });
        // B's own customer echoes the number B typed; what matters is that it is B's, and A's is untouched.
        CASES.put("POST /api/v1/customers", (a, b) -> {
            ApiResponse created = b.owner().post("/api/v1/customers", Map.of("name", "B's buyer", "phone", a.customerPhone()))
                    .expectStatus(201);
            assertThat(created.<String>json("$.id")).isNotEqualTo(a.customerId().toString());
            assertThat(a.owner().get("/api/v1/customers/" + a.customerId()).<String>json("$.name")).isEqualTo(a.customerName());
        assertThat(a.owner().get("/api/v1/delivery-zones/quote?areaId=365").<String>json("$.zoneId")).isEqualTo(a.zoneId().toString());
        assertThat(a.owner().get("/api/v1/orders/" + a.orderId()).<String>json("$.note")).startsWith("Order ");
        });
        CASES.put("GET /api/v1/customers/{id}", (a, b) -> expectNotFound(b.owner().get("/api/v1/customers/" + a.customerId())));
        CASES.put("PATCH /api/v1/customers/{id}", (a, b) -> expectNotFound(
                b.owner().patch("/api/v1/customers/" + a.customerId(), Map.of("name", "Taken over"))));

        // Geography: the same for everyone, nothing of a business in it.
        CASES.put("GET /api/v1/geo/divisions", (a, b) -> expectNoLeak(b.owner().get("/api/v1/geo/divisions"), a));
        CASES.put("GET /api/v1/geo/districts", (a, b) -> expectNoLeak(b.owner().get("/api/v1/geo/districts"), a));
        CASES.put("GET /api/v1/geo/districts/{id}/areas", (a, b) -> expectNoLeak(
                b.owner().get("/api/v1/geo/districts/47/areas"), a));

        // Orders.
        CASES.put("POST /api/v1/orders", (a, b) -> {
            // A's customer and A's variant mean nothing in B: refused like unknown ids.
            b.owner().post("/api/v1/orders", Map.of("customerId", a.customerId().toString(), "items",
                    List.of(Map.of("variantId", b.variantId().toString(), "quantity", 1)))).expectError(400, "validation.failed");
            b.owner().post("/api/v1/orders", Map.of("customerId", b.customerId().toString(), "items",
                    List.of(Map.of("variantId", a.variantId().toString(), "quantity", 1)))).expectError(400, "validation.failed");
            expectNoLeak(b.owner().post("/api/v1/orders", Map.of("customerId", b.customerId().toString(), "items",
                    List.of(Map.of("variantId", b.variantId().toString(), "quantity", 1)))).expectStatus(201), a);
        });
        CASES.put("GET /api/v1/orders", (a, b) -> {
            expectNoLeak(b.owner().get("/api/v1/orders"), a);
            expectNoLeak(b.owner().get("/api/v1/orders?number=1001"), a);
        });
        CASES.put("GET /api/v1/orders/{id}", (a, b) -> expectNotFound(b.owner().get("/api/v1/orders/" + a.orderId())));
        CASES.put("POST /api/v1/orders/{id}/confirm", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/orders/" + a.orderId() + "/confirm", null));
            assertThat(a.owner().get("/api/v1/orders/" + a.orderId()).<String>json("$.status")).isEqualTo("DRAFT");
        });
        CASES.put("POST /api/v1/orders/{id}/pack", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/orders/" + a.orderId() + "/pack", null)));
        CASES.put("POST /api/v1/orders/{id}/ship", (a, b) -> expectNotFound(
                b.owner().post("/api/v1/orders/" + a.orderId() + "/ship", Map.of("courier", "Pathao"))));
        CASES.put("POST /api/v1/orders/{id}/deliver", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/orders/" + a.shippedOrderId() + "/deliver", null));
            assertThat(a.owner().get("/api/v1/orders/" + a.shippedOrderId()).<String>json("$.status")).isEqualTo("SHIPPED");
        });
        // In bulk, another business's order is "not found" in its own result line.
        CASES.put("POST /api/v1/orders/bulk/pack", (a, b) -> {
            ApiResponse results = b.owner().post("/api/v1/orders/bulk/pack", Map.of("orderIds", List.of(a.orderId().toString())))
                    .expectStatus(200);
            // The line echoes the id B sent, and nothing of A's order.
            assertThat(results.<List<String>>json("$[*].code")).containsExactly("resource.not_found");
            assertThat(results.<List<Object>>json("$[*].numberLabel")).containsOnlyNulls();
            assertThat(a.owner().get("/api/v1/orders/" + a.orderId()).<String>json("$.status")).isEqualTo("DRAFT");
        });
        CASES.put("POST /api/v1/orders/bulk/ship", (a, b) -> {
            ApiResponse results = b.owner().post("/api/v1/orders/bulk/ship", Map.of("courier", "Pathao",
                    "orders", List.of(Map.of("orderId", a.orderId().toString())))).expectStatus(200);
            assertThat(results.<List<String>>json("$[*].code")).containsExactly("resource.not_found");
            assertThat(a.owner().get("/api/v1/orders/" + a.orderId()).<String>json("$.status")).isEqualTo("DRAFT");
        });
        CASES.put("POST /api/v1/orders/{id}/payments", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/orders/" + a.orderId() + "/payments", Map.of("method", "CASH", "amount", 100)));
            assertThat(a.owner().get("/api/v1/orders/" + a.orderId()).<Integer>json("$.amountPaid")).isEqualTo(5000);
        });
        CASES.put("GET /api/v1/shipments", (a, b) -> {
            expectNoLeak(b.owner().get("/api/v1/shipments"), a);
            expectNoLeak(b.owner().get("/api/v1/shipments?courier=" + a.courier().replace(" ", "%20")), a);
        });
        CASES.put("POST /api/v1/shipments/settle-cod", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/shipments/settle-cod", Map.of("shipmentIds", List.of(a.shipmentId().toString()))));
            assertThat(a.owner().get("/api/v1/orders/" + a.shippedOrderId()).<String>json("$.shipments[0].codStatus"))
                    .isEqualTo("WITH_COURIER");
        });
        CASES.put("GET /api/v1/shipments/couriers", (a, b) -> expectNoLeak(b.owner().get("/api/v1/shipments/couriers"), a));
        CASES.put("POST /api/v1/orders/{id}/cancel", (a, b) -> {
            expectNotFound(b.owner().post("/api/v1/orders/" + a.orderId() + "/cancel", Map.of("reason", "Not mine")));
            assertThat(a.owner().get("/api/v1/orders/" + a.orderId()).<String>json("$.status")).isEqualTo("DRAFT");
        });
        CASES.put("PATCH /api/v1/orders/{id}", (a, b) -> expectNotFound(
                b.owner().patch("/api/v1/orders/" + a.orderId(), Map.of("note", "Taken over"))));

        // Delivery zones.
        CASES.put("GET /api/v1/delivery-zones", (a, b) -> expectNoLeak(b.owner().get("/api/v1/delivery-zones"), a));
        CASES.put("GET /api/v1/delivery-zones/quote", (a, b) -> {
            // B's own Savar zone answers, never A's.
            ApiResponse quote = b.owner().get("/api/v1/delivery-zones/quote?areaId=365").expectStatus(200);
            expectNoLeak(quote, a);
            assertThat(quote.<String>json("$.zoneId")).isEqualTo(b.zoneId().toString());
        });
        CASES.put("PUT /api/v1/delivery-zones", (a, b) -> {
            // A's zone id means nothing in B: refused like any unknown id, and A's zones stay.
            b.owner().put("/api/v1/delivery-zones", Map.of("zones", List.of(Map.of("id", a.zoneId().toString(),
                    "name", "Mine now", "charge", 1, "isDefault", true)))).expectError(400, "validation.failed");
            assertThat(a.owner().get("/api/v1/delivery-zones").<List<String>>json("$[*].name")).contains(a.zoneName());
        });

        CASES.put("GET /api/v1/imports/template.xlsx", (a, b) -> assertThat(
                b.owner().download("/api/v1/imports/template.xlsx").statusCode()).isEqualTo(200));

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
        ApiResponse product = a.owner().get("/api/v1/products/" + a.productId()).expectStatus(200);
        assertThat(product.<String>json("$.name")).isEqualTo(a.productName());
        assertThat(product.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(product.<String>json("$.categoryId")).isEqualTo(a.subcategoryId().toString());
        assertThat(product.<Integer>json("$.variants[0].price")).isEqualTo(120000);
        // 15 in, 12 damaged, 1 shipped (the fixture's shipped order).
        assertThat(product.<Integer>json("$.variants[0].onHand")).isEqualTo(2);
        assertThat(a.owner().get("/api/v1/variants/" + a.variantId() + "/movements").<List<String>>json("$.items[*].type"))
                .containsExactly("SHIP", "RESERVE", "DAMAGE", "RECEIVE", "OPENING");
        ApiResponse shipped = a.owner().get("/api/v1/orders/" + a.shippedOrderId());
        assertThat(shipped.<String>json("$.status")).isEqualTo("SHIPPED");
        assertThat(shipped.<String>json("$.shipments[0].courier")).isEqualTo(a.courier());
        ApiResponse notifications = a.owner().get("/api/v1/notifications").expectStatus(200);
        assertThat(notifications.<List<String>>json("$.items[*].id")).containsExactly(a.notificationId().toString());
        assertThat(notifications.<Boolean>json("$.items[0].read")).isTrue();
        assertThat(notifications.<Integer>json("$.unread")).isZero();
        assertThat(a.owner().get("/api/v1/imports/" + a.importId() + "/preview").<String>json("$.rows[0].name"))
                .startsWith("Imported ");
        assertThat(a.owner().get("/api/v1/customers/" + a.customerId()).<String>json("$.name")).isEqualTo(a.customerName());
        assertThat(a.owner().get("/api/v1/customers?phone=" + a.customerPhone().replace("+", "%2B")).<String>json("$.items[0].id"))
                .isEqualTo(a.customerId().toString());
        assertThat(product.<String>json("$.variants[0].status")).isEqualTo("ACTIVE");
        assertThat(product.<List<String>>json("$.images[*].id")).containsExactly(a.productImageId().toString());
        assertThat(a.owner().get("/api/v1/business").<String>json("$.logo.small")).contains(a.fileId().toString());
        assertThat(a.owner().download("/api/v1/files/" + a.fileId() + "/large").statusCode()).isEqualTo(200);
    }

    private UUID membershipId(UUID businessId, UUID userId) {
        return systemDatabase.jdbc().sql("select id from memberships where business_id = ? and user_id = ?")
                .params(businessId, userId).query(UUID.class).single();
    }
}
