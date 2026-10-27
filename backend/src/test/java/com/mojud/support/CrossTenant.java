package com.mojud.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Tools for the cross-tenant suite (backend-plan.md §4): two fully set-up businesses, the list of
 * every endpoint, and the two ways an endpoint can leak.
 */
public final class CrossTenant {

    private static final Pattern INVITE_LINK = Pattern.compile("/invites#([A-Za-z0-9_-]+)");

    private CrossTenant() {
    }

    /** One business with an owner, a staff member, changed settings and an open invite. */
    public record Tenant(ApiClient owner, TestAccounts.Account ownerAccount, UUID ownerId, UUID businessId,
            String name, String phone, String orderPrefix, ApiClient staff, TestAccounts.Account staffAccount,
            UUID staffId, UUID membershipIdOfStaff, UUID inviteId, String invitedEmail, String inviteToken,
            UUID categoryId, String categoryName, UUID subcategoryId, String subcategoryName, UUID tagId, String tagName,
            UUID productId, String productName, UUID variantId, String sku, UUID fileId, UUID productImageId,
            UUID notificationId, UUID importId, UUID customerId, String customerName, String customerPhone,
            UUID zoneId, String zoneName, UUID orderId) {

        /** Values that must never appear in another business's answers. */
        public Set<String> markers() {
            return Set.of(businessId.toString(), name, phone, orderPrefix, ownerId.toString(), staffId.toString(),
                    ownerAccount.email(), staffAccount.email(), inviteId.toString(), invitedEmail,
                    categoryId.toString(), categoryName, subcategoryId.toString(), subcategoryName,
                    tagId.toString(), tagName, productId.toString(), productName, variantId.toString(), sku,
                    fileId.toString(), productImageId.toString(), notificationId.toString(), importId.toString(),
                    customerId.toString(), customerName, customerPhone, zoneId.toString(), zoneName, orderId.toString());
        }
    }

    /** Builds a tenant through the API only, as a seller would. */
    public static Tenant setUp(int port, CapturedMessages messages, MembershipIds membershipIds) {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        ApiClient owner = new ApiClient(port);
        TestAccounts.Account ownerAccount = TestAccounts.signUp(owner, messages);
        String name = "Shop " + marker;
        String phone = "+88" + TestAccounts.newPhone();
        UUID businessId = UUID.fromString(owner.post("/api/v1/businesses",
                Map.of("name", name, "phone", phone, "address", "Road " + marker)).expectStatus(201).json("$.id"));
        String prefix = randomPrefix();
        owner.patch("/api/v1/business/settings", Map.of("orderNumberPrefix", prefix)).expectStatus(200);

        ApiClient staff = new ApiClient(port);
        TestAccounts.Account staffAccount = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", staffAccount.email(), "role", "STAFF")).expectStatus(201);
        staff.post("/api/v1/invites/accept", Map.of("token", lastInviteToken(messages, staffAccount.email())))
                .expectStatus(200);
        UUID staffId = TestBusinesses.userId(staff);

        String invitedEmail = TestAccounts.newEmail();
        UUID inviteId = UUID.fromString(owner.post("/api/v1/staff/invites", Map.of("to", invitedEmail, "role", "MANAGER"))
                .expectStatus(201).json("$.id"));
        String categoryName = "Category " + marker;
        UUID categoryId = UUID.fromString(owner.post("/api/v1/categories", Map.of("name", categoryName))
                .expectStatus(201).json("$.id"));
        String subcategoryName = "Subcategory " + marker;
        UUID subcategoryId = UUID.fromString(owner.post("/api/v1/categories",
                Map.of("name", subcategoryName, "parentId", categoryId.toString())).expectStatus(201).json("$.id"));
        String tagName = "tag-" + marker;
        UUID tagId = UUID.fromString(owner.post("/api/v1/tags", Map.of("name", tagName)).expectStatus(201).json("$.id"));
        String productName = "Product " + marker;
        String sku = "SKU-" + marker;
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", productName, "price", 120000,
                "costPrice", 80000, "sku", sku, "categoryId", subcategoryId.toString(), "tagIds", List.of(tagId.toString()),
                "openingStock", 10)).expectStatus(201);
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", product.json("$.variants[0].id"),
                "kind", "RECEIVE", "quantity", 5)), "note", "Delivery " + marker), Map.of("Idempotency-Key", "fixture-" + marker))
                .expectStatus(201);
        // A second product with options, so option rows exist too.
        owner.post("/api/v1/products", Map.of("name", "Gridded " + marker, "price", 50000,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201);
        UUID fileId = UUID.fromString(owner.upload("/api/v1/uploads", "photo.jpg", TestImages.jpeg(400, 400))
                .expectStatus(201).json("$.id"));
        UUID productImageId = UUID.fromString(owner.post("/api/v1/products/" + product.json("$.id") + "/images",
                Map.of("fileId", fileId.toString())).expectStatus(200).json("$.images[0].id"));
        owner.put("/api/v1/business/logo", Map.of("fileId", fileId.toString())).expectStatus(200);
        // Down to 3 of 15, below the default alert level of 5: a low-stock notification.
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", product.json("$.variants[0].id"),
                "kind", "DAMAGE", "quantity", 12)))).expectStatus(201);
        UUID notificationId = UUID.fromString(owner.get("/api/v1/notifications").expectStatus(200).json("$.items[0].id"));
        owner.post("/api/v1/notifications/read", Map.of("ids", List.of(notificationId.toString()))).expectStatus(204);
        UUID importId = UUID.fromString(owner.upload("/api/v1/imports", "stock-" + marker + ".csv",
                ("Name,Price,SKU\nImported " + marker + ",100,IMP-" + marker + "\n").getBytes(StandardCharsets.UTF_8))
                .expectStatus(201).json("$.id"));
        String customerName = "Customer " + marker;
        String customerPhone = "+88" + TestAccounts.newPhone();
        UUID customerId = UUID.fromString(owner.post("/api/v1/customers", Map.of("name", customerName,
                "phone", customerPhone, "address", "House " + marker, "areaId", 9013)).expectStatus(201).json("$.id"));
        // Its own zones: Savar alone at ৳95 (so every zone table has rows), Dhaka district, the rest.
        String zoneName = "Zone " + marker;
        UUID zoneId = UUID.fromString(owner.put("/api/v1/delivery-zones", Map.of("zones", List.of(
                Map.of("name", zoneName, "charge", 9500, "areaIds", List.of(365)),
                Map.of("name", "Inside Dhaka", "charge", 7000, "districtIds", List.of(47)),
                Map.of("name", "Outside Dhaka", "charge", 13000, "isDefault", true)))).expectStatus(200).json("$[0].id"));
        UUID orderId = UUID.fromString(owner.post("/api/v1/orders", Map.of("customerId", customerId.toString(),
                "items", List.of(Map.of("variantId", product.<String>json("$.variants[0].id"), "quantity", 1)),
                "note", "Order " + marker)).expectStatus(201).json("$.id"));
        return new Tenant(owner, ownerAccount, TestBusinesses.userId(owner), businessId, name, phone, prefix, staff,
                staffAccount, staffId, membershipIds.of(businessId, staffId), inviteId, invitedEmail,
                lastInviteToken(messages, invitedEmail), categoryId, categoryName, subcategoryId, subcategoryName,
                tagId, tagName, UUID.fromString(product.json("$.id")), productName,
                UUID.fromString(product.json("$.variants[0].id")), sku, fileId, productImageId, notificationId, importId,
                customerId, customerName, customerPhone, zoneId, zoneName, orderId);
    }

    /** Looks up a membership id; tests pass a query through the system role. */
    @FunctionalInterface
    public interface MembershipIds {

        UUID of(UUID businessId, UUID userId);
    }

    /** Every mapped endpoint as {@code "METHOD /path/{pattern}"}, from the application itself. */
    public static Set<String> endpoints(RequestMappingHandlerMapping mapping) {
        Set<String> endpoints = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (isFramework(handler)) {
                return;
            }
            for (String path : info.getPatternValues()) {
                info.getMethodsCondition().getMethods()
                        .forEach(method -> endpoints.add(method.name() + " " + path));
            }
        });
        return endpoints;
    }

    /** Endpoints without a case, and cases for endpoints that no longer exist. */
    public static void assertEveryEndpointHasACase(Set<String> endpoints, Set<String> cases) {
        Set<String> missing = new TreeSet<>(endpoints);
        missing.removeAll(cases);
        Set<String> stale = new TreeSet<>(cases);
        stale.removeAll(endpoints);
        assertThat(missing).as("endpoints with no cross-tenant case: add one to CrossTenantTests").isEmpty();
        assertThat(stale).as("cross-tenant cases for endpoints that no longer exist").isEmpty();
    }

    /** Another business's id must look like an id that does not exist. */
    public static void expectNotFound(ApiResponse response) {
        assertThat(response.status()).as("expected 404 for another business's id; body: %s", response.body())
                .isEqualTo(404);
    }

    /** The answer may be anything except a success, and must not contain the other business's data. */
    public static void expectRefused(ApiResponse response, Tenant other) {
        assertThat(response.status()).as("expected a refusal; body: %s", response.body()).isBetween(400, 499);
        expectNoLeak(response, other);
    }

    public static void expectNoLeak(ApiResponse response, Tenant other) {
        assertThat(response.status()).as("body: %s", response.body()).isLessThan(500);
        for (String marker : other.markers()) {
            assertThat(response.body()).as("answer leaks another business's data (%s)", marker).doesNotContain(marker);
        }
    }

    private static boolean isFramework(HandlerMethod handler) {
        String type = handler.getBeanType().getName();
        return type.startsWith("org.springdoc.") || type.startsWith("org.springframework.");
    }

    private static String randomPrefix() {
        StringBuilder prefix = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 9; i++) {
            prefix.append((char) ('A' + random.nextInt(26)));
        }
        return prefix.toString();
    }

    private static String lastInviteToken(CapturedMessages messages, String destination) {
        var received = messages.to(destination);
        Matcher link = INVITE_LINK.matcher(received.get(received.size() - 1).body());
        if (!link.find()) {
            throw new AssertionError("No invite link sent to " + destination);
        }
        return link.group(1);
    }
}
