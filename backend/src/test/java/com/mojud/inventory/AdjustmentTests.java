package com.mojud.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Stock adjustments and history through the API (implementation-plan.md 4a). */
@IntegrationTest
class AdjustmentTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;
    String small;
    String large;

    @BeforeEach
    void productWithOpeningStock() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Adjusting Shop");
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 1500, "openingStock", 10,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "L"))))).expectStatus(201);
        small = product.json("$.variants[0].id");
        large = product.json("$.variants[1].id");
        assertThat(product.<List<Integer>>json("$.variants[*].onHand")).containsExactly(10, 10);
    }

    @Test
    void receiveDamageAndCountInOneRequest() {
        ApiResponse result = owner.post("/api/v1/stock/adjustments", Map.of("note", "Delivery from Islampur", "lines", List.of(
                line(small, "RECEIVE", 5), line(large, "COUNT", 7)))).expectStatus(201);

        assertThat(result.<List<Integer>>json("$.lines[*].onHand")).containsExactly(15, 7);
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(line(small, "DAMAGE", 2)))).expectStatus(201);
        ApiResponse product = owner.get("/api/v1/products/" + productId());
        assertThat(product.<List<Integer>>json("$.variants[*].available")).containsExactly(13, 7);
        assertThat(owner.get("/api/v1/products").<Integer>json("$.items[0].available")).isEqualTo(20);
    }

    @Test
    void theHistoryShowsEachChangeNewestFirstWithWhoAndWhy() {
        owner.post("/api/v1/stock/adjustments", Map.of("note", "Delivery", "lines", List.of(line(small, "RECEIVE", 5))))
                .expectStatus(201);
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(line(small, "COUNT", 12)))).expectStatus(201);

        ApiResponse history = owner.get("/api/v1/variants/" + small + "/movements").expectStatus(200);

        assertThat(history.<List<String>>json("$.items[*].type")).containsExactly("ADJUST", "RECEIVE", "OPENING");
        assertThat(history.<List<Integer>>json("$.items[*].onHandChange")).containsExactly(-3, 5, 10);
        assertThat(history.<List<Integer>>json("$.items[*].onHandAfter")).containsExactly(12, 15, 10);
        assertThat(history.<String>json("$.items[1].note")).isEqualTo("Delivery");
        assertThat(history.<String>json("$.items[1].by.name")).isEqualTo("Test Seller");
    }

    @Test
    void aRequestWithAShortLineChangesNothing() {
        ApiResponse refused = owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(
                line(small, "RECEIVE", 5), line(large, "DAMAGE", 11)))).expectError(409, "stock.insufficient");

        assertThat(refused.<List<String>>json("$.shortages[*].variantId")).containsExactly(large);
        assertThat(owner.get("/api/v1/products/" + productId()).<List<Integer>>json("$.variants[*].onHand"))
                .containsExactly(10, 10);
    }

    @Test
    void aResentRequestRunsOnceAndAReusedKeyIsRefused() {
        Map<String, Object> body = Map.of("lines", List.of(line(small, "RECEIVE", 5)));
        Map<String, String> key = Map.of("Idempotency-Key", "receipt-7");

        ApiResponse first = owner.post("/api/v1/stock/adjustments", body, key).expectStatus(201);
        ApiResponse again = owner.post("/api/v1/stock/adjustments", body, key).expectStatus(201);

        assertThat(again.body()).isEqualTo(first.body());
        assertThat(owner.get("/api/v1/products/" + productId()).<Integer>json("$.variants[0].onHand")).isEqualTo(15);
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(line(small, "RECEIVE", 6))), key)
                .expectError(409, "idempotency.key_reused");
    }

    @Test
    void adjustmentsAreValidatedAndAudited() {
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(line(small, "RECEIVE", 0))))
                .expectError(400, "validation.failed");
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(line(small, "RECEIVE", 1), line(small, "DAMAGE", 1))))
                .expectError(400, "validation.failed");
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of())).expectError(400, "validation.failed");

        owner.post("/api/v1/stock/adjustments", Map.of("note", "Shelf count", "lines", List.of(line(small, "COUNT", 0))))
                .expectStatus(201);

        ApiResponse log = owner.get("/api/v1/business/audit-log");
        assertThat(log.<String>json("$.items[0].action")).isEqualTo("stock.adjusted");
        assertThat(log.<String>json("$.items[0].details.note")).isEqualTo("Shelf count");
    }

    @Test
    void staffMayAdjustStock() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);

        staff.post("/api/v1/stock/adjustments", Map.of("lines", List.of(line(small, "RECEIVE", 3)))).expectStatus(201);
        staff.get("/api/v1/variants/" + small + "/movements").expectStatus(200);
    }

    private String productId() {
        return owner.get("/api/v1/products").json("$.items[0].id");
    }

    private static Map<String, Object> line(String variantId, String kind, int quantity) {
        return Map.of("variantId", variantId, "kind", kind, "quantity", quantity);
    }
}
