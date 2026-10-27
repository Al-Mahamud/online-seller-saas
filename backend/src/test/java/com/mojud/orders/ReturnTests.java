package com.mojud.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Returns and partial delivery (implementation-plan.md 8c). Each order: 2 × S and 1 × M at ৳1,000
 * to Mirpur (৳70): total ৳3,070 (307000 poisha).
 */
@IntegrationTest
class ReturnTests {

    static final int MIRPUR = 9027;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    ApiClient owner;
    String productId;
    String small;
    String medium;
    String customerId;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Return Shop");
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100000, "openingStock", 10,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201);
        productId = product.json("$.id");
        small = product.json("$.variants[0].id");
        medium = product.json("$.variants[1].id");
        customerId = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678",
                "address", "House 4, Road 2", "areaId", MIRPUR)).expectStatus(201).json("$.id");
    }

    @Test
    void aFailedDeliveryBringsEverythingBackAndOwesNothing() {
        String id = shipped(Map.of("advancePaid", 50000, "advanceMethod", "BKASH"));
        ApiResponse order = owner.get("/api/v1/orders/" + id);
        String smallItem = itemId(order, "S");
        String mediumItem = itemId(order, "M");

        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Wrong address",
                "items", List.of(back(smallItem, 1, "RESTOCK")))).expectError(400, "validation.failed");

        ApiResponse returned = owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY",
                "reason", "Customer not reachable", "items", List.of(back(smallItem, 1, "RESTOCK"),
                        back(smallItem, 1, "WRITE_OFF"), back(mediumItem, 1, "RESTOCK")))).expectStatus(201);

        assertThat(returned.<String>json("$.status")).isEqualTo("RETURNED");
        assertThat(returned.<String>json("$.stockState")).isEqualTo("NONE");
        assertThat(returned.<String>json("$.shipments[0].status")).isEqualTo("RETURNED");
        assertThat(returned.<String>json("$.shipments[0].codStatus")).isEqualTo("NONE");
        assertThat(money(returned)).isEqualTo(List.of(307000, 307000, 0, 50000, -50000, "REFUND_DUE"));
        assertThat(returned.<String>json("$.returns[0].kind")).isEqualTo("FAILED_DELIVERY");
        assertThat(returned.<List<String>>json("$.returns[0].items[*].disposition")).containsExactlyInAnyOrder(
                "RESTOCK", "WRITE_OFF", "RESTOCK");
        assertThat(stock(small)).isEqualTo(9);
        assertThat(stock(medium)).isEqualTo(10);
        assertThat(owner.get("/api/v1/variants/" + small + "/movements").<List<String>>json("$.items[0,1].type"))
                .containsExactlyInAnyOrder("RETURN", "RETURN_DAMAGED");
        assertThat(events(id)).endsWith("SHIPPED->RETURNED");
    }

    @Test
    void aFailedDeliveryWithTheAdvanceRefunded() {
        String id = shipped(Map.of("advancePaid", 50000, "advanceMethod", "BKASH"));

        ApiResponse returned = owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY",
                "reason", "Refused", "refund", Map.of("amount", 50000, "method", "BKASH", "reference", "RF-1")))
                .expectStatus(201);

        assertThat(money(returned)).isEqualTo(List.of(307000, 307000, 0, 0, 0, "PAID"));
        assertThat(returned.<Integer>json("$.advancePaid")).isZero();
        assertThat(returned.<Integer>json("$.returns[0].refundAmount")).isEqualTo(50000);
        assertThat(stock(small)).isEqualTo(10);
    }

    @Test
    void aFailedDeliveryCanBeShippedAgain() {
        String id = shipped(Map.of("advancePaid", 50000, "advanceMethod", "BKASH"));
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Not home"))
                .expectStatus(201);
        assertThat(stock(small)).isEqualTo(10);

        ApiResponse again = owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "RedX")).expectStatus(200);

        assertThat(again.<String>json("$.status")).isEqualTo("SHIPPED");
        assertThat(stock(small)).isEqualTo(8);
        assertThat(owner.get("/api/v1/variants/" + small + "/movements").<String>json("$.items[0].type")).isEqualTo("SALE");
        assertThat(again.<List<String>>json("$.shipments[*].status")).containsExactly("RETURNED", "IN_TRANSIT");
        assertThat(again.<Integer>json("$.shipments[1].codExpected")).isEqualTo(257000);
        assertThat(money(again)).isEqualTo(List.of(307000, 0, 307000, 50000, 257000, "PARTIAL"));
        assertThat(money(owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200)))
                .isEqualTo(List.of(307000, 0, 307000, 307000, 0, "PAID"));
    }

    @Test
    void partialDeliveryCollectsOnlyForWhatWasKept() {
        String id = shipped(Map.of());
        String mediumItem = itemId(owner.get("/api/v1/orders/" + id), "M");
        String smallItem = itemId(owner.get("/api/v1/orders/" + id), "S");

        owner.post("/api/v1/orders/" + id + "/deliver", Map.of("refused", List.of(back(smallItem, 2, "RESTOCK"),
                back(mediumItem, 1, "RESTOCK")))).expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + id + "/deliver", Map.of("codCollected", 207001,
                "refused", List.of(back(mediumItem, 1, "RESTOCK")))).expectError(400, "validation.failed");

        ApiResponse delivered = owner.post("/api/v1/orders/" + id + "/deliver", Map.of("reason", "Did not like the colour",
                "refused", List.of(back(mediumItem, 1, "RESTOCK")))).expectStatus(200);

        assertThat(delivered.<String>json("$.status")).isEqualTo("DELIVERED");
        assertThat(delivered.<Integer>json("$.shipments[0].codCollected")).isEqualTo(207000);
        assertThat(money(delivered)).isEqualTo(List.of(307000, 100000, 207000, 207000, 0, "PAID"));
        assertThat(delivered.<String>json("$.returns[0].kind")).isEqualTo("PARTIAL_DELIVERY");
        assertThat(delivered.<String>json("$.returns[0].reason")).isEqualTo("Did not like the colour");
        assertThat(stock(medium)).isEqualTo(10);
        assertThat(stock(small)).isEqualTo(8);
    }

    @Test
    void customerReturnsAfterDeliveryUntilEverythingIsBack() {
        String id = shipped(Map.of());
        owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200);
        ApiResponse order = owner.get("/api/v1/orders/" + id);
        String smallItem = itemId(order, "S");
        String mediumItem = itemId(order, "M");

        ApiResponse first = owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "CUSTOMER_RETURN",
                "reason", "Torn seam", "items", List.of(back(smallItem, 1, "WRITE_OFF")),
                "refund", Map.of("amount", 100000, "method", "CASH"))).expectStatus(201);
        assertThat(first.<String>json("$.status")).isEqualTo("DELIVERED");
        assertThat(money(first)).isEqualTo(List.of(307000, 100000, 207000, 207000, 0, "PAID"));
        assertThat(stock(small)).isEqualTo(8);

        // More than was shipped, counting the first return: refused.
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Again",
                "items", List.of(back(smallItem, 2, "RESTOCK")))).expectError(400, "validation.failed");
        // A refund the customer did not overpay: refused.
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Again",
                "items", List.of(back(smallItem, 1, "RESTOCK")), "refund", Map.of("amount", 100001, "method", "CASH")))
                .expectError(400, "validation.failed");

        ApiResponse rest = owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "CUSTOMER_RETURN",
                "reason", "Changed mind", "items", List.of(back(smallItem, 1, "RESTOCK"), back(mediumItem, 1, "RESTOCK")),
                "refund", Map.of("amount", 200000, "method", "BKASH", "reference", "RF-77"))).expectStatus(201);

        assertThat(rest.<String>json("$.status")).isEqualTo("RETURNED");
        // The goods are all back; the delivery charge stays the customer's.
        assertThat(money(rest)).isEqualTo(List.of(307000, 300000, 7000, 7000, 0, "PAID"));
        assertThat(rest.<List<String>>json("$.returns[*].kind")).containsExactly("CUSTOMER_RETURN", "CUSTOMER_RETURN");
        assertThat(stock(small)).isEqualTo(9);
        assertThat(stock(medium)).isEqualTo(10);
        assertThat(events(id)).endsWith("DELIVERED->RETURNED");
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao")).expectError(409, "order.invalid_transition");
    }

    @Test
    void returnsOnlyFromTheRightPlace() {
        String id = shipped(Map.of());
        String smallItem = itemId(owner.get("/api/v1/orders/" + id), "S");

        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Early",
                "items", List.of(back(smallItem, 1, "RESTOCK")))).expectError(409, "order.invalid_transition");
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "PARTIAL_DELIVERY", "reason", "No",
                "items", List.of(back(smallItem, 1, "RESTOCK")))).expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", " "))
                .expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Lost",
                "items", List.of(back(UUID.randomUUID().toString(), 1, "RESTOCK")))).expectError(400, "validation.failed");

        owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200);
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Late"))
                .expectError(409, "order.invalid_transition");
        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Nothing"))
                .expectError(400, "validation.failed");
    }

    @Test
    void withStockTakenAtConfirmationAReturnAndReShipKeepStockRight() {
        owner.patch("/api/v1/business/settings", Map.of("stockCommitPoint", "CONFIRM")).expectStatus(200);
        String id = shipped(Map.of());
        assertThat(stock(small)).isEqualTo(8);

        owner.post("/api/v1/orders/" + id + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Not home")).expectStatus(201);
        assertThat(stock(small)).isEqualTo(10);
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao")).expectStatus(200);
        assertThat(stock(small)).isEqualTo(8);
        assertThat(systemDatabase.jdbc().sql("""
                        select bool_and(l.on_hand = s.on_hand and l.reserved = s.reserved) from stock_levels l
                        join (select variant_id, sum(on_hand_delta) on_hand, sum(reserved_delta) reserved
                              from stock_movements group by variant_id) s on s.variant_id = l.variant_id
                        where l.variant_id in (?, ?)
                        """)
                .params(UUID.fromString(small), UUID.fromString(medium)).query(Boolean.class).single()).isTrue();
    }

    /** A shipped order of 2 × S and 1 × M. */
    private String shipped(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("customerId", customerId, "items", List.of(
                Map.of("variantId", small, "quantity", 2), Map.of("variantId", medium, "quantity", 1))));
        body.putAll(extra);
        String id = owner.post("/api/v1/orders", body).expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao")).expectStatus(200);
        return id;
    }

    private static String itemId(ApiResponse order, String label) {
        return order.<List<String>>json("$.items[?(@.variantLabel == '" + label + "')].id").get(0);
    }

    private static Map<String, Object> back(String orderItemId, int quantity, String disposition) {
        return Map.of("orderItemId", orderItemId, "quantity", quantity, "disposition", disposition);
    }

    private int stock(String variantId) {
        return owner.get("/api/v1/products/" + productId)
                .<List<Integer>>json("$.variants[?(@.id == '" + variantId + "')].onHand").get(0);
    }

    private List<String> events(String orderId) {
        return systemDatabase.jdbc().sql("""
                        select coalesce(from_status, '') || '->' || to_status from order_events
                        where order_id = ? order by created_at, id
                        """)
                .param(UUID.fromString(orderId)).query(String.class).list();
    }

    /** [total, returnCredit, netTotal, amountPaid, balanceDue, paymentStatus] */
    private static List<Object> money(ApiResponse order) {
        return List.of(order.json("$.total"), order.json("$.returnCredit"), order.json("$.netTotal"),
                order.json("$.amountPaid"), order.json("$.balanceDue"), order.json("$.paymentStatus"));
    }
}
