package com.mojud.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Confirm, edit while confirmed, and cancel (implementation-plan.md 7b, backend-plan.md §6). */
@IntegrationTest
class OrderTransitionTests {

    static final int MIRPUR = 9027;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    ApiClient owner;
    UUID business;
    String productId;
    String small;
    String medium;
    String customerId;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Transition Shop");
        ApiResponse kurti = owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 120000, "openingStock", 10,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201);
        productId = kurti.json("$.id");
        small = kurti.json("$.variants[0].id");
        medium = kurti.json("$.variants[1].id");
        customerId = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678",
                "address", "House 4, Road 2", "areaId", MIRPUR)).expectStatus(201).json("$.id");
    }

    @Test
    void confirmingReservesTheItems() {
        String id = order(small, 3);

        ApiResponse confirmed = owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);

        assertThat(confirmed.<String>json("$.status")).isEqualTo("CONFIRMED");
        assertThat(confirmed.<String>json("$.stockState")).isEqualTo("RESERVED");
        assertThat(confirmed.<String>json("$.confirmedAt")).isNotNull();
        assertThat(stock(small)).isEqualTo(List.of(10, 3, 7));
        ApiResponse history = owner.get("/api/v1/variants/" + small + "/movements");
        assertThat(history.<String>json("$.items[0].type")).isEqualTo("RESERVE");
        assertThat(history.<String>json("$.items[0].referenceType")).isEqualTo("ORDER");
        assertThat(history.<String>json("$.items[0].referenceId")).isEqualTo(id);
        assertThat(events(id)).containsExactly("->DRAFT", "DRAFT->CONFIRMED");
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("order.confirmed");
        ApiResponse customer = owner.get("/api/v1/customers/" + customerId);
        assertThat(customer.<Integer>json("$.orderCount")).isEqualTo(1);
        assertThat(customer.<String>json("$.lastOrderAt")).isNotNull();

        owner.post("/api/v1/orders/" + id + "/confirm", null).expectError(409, "order.invalid_transition")
                .json("$.status");
        assertThat(owner.post("/api/v1/orders/" + id + "/confirm", null).<String>json("$.status")).isEqualTo("CONFIRMED");
    }

    @Test
    void withStockTakenAtConfirmationConfirmingTakesIt() {
        owner.patch("/api/v1/business/settings", Map.of("stockCommitPoint", "CONFIRM")).expectStatus(200);
        String id = order(small, 3);

        ApiResponse confirmed = owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);

        assertThat(confirmed.<String>json("$.stockState")).isEqualTo("DEDUCTED");
        assertThat(stock(small)).isEqualTo(List.of(7, 0, 7));
        assertThat(owner.get("/api/v1/variants/" + small + "/movements").<String>json("$.items[0].type")).isEqualTo("SALE");
    }

    @Test
    void aShortItemRefusesTheOrderAndNamesIt() {
        String id = order(small, 11);

        ApiResponse refused = owner.post("/api/v1/orders/" + id + "/confirm", null).expectError(409, "stock.insufficient");

        assertThat(refused.<String>json("$.shortages[0].productName")).isEqualTo("Cotton Kurti");
        assertThat(refused.<String>json("$.shortages[0].variantLabel")).isEqualTo("S");
        assertThat(refused.<String>json("$.shortages[0].sku")).isEqualTo("COTTONKU-S");
        assertThat(refused.<Integer>json("$.shortages[0].requested")).isEqualTo(11);
        assertThat(refused.<Integer>json("$.shortages[0].available")).isEqualTo(10);
        assertThat(owner.get("/api/v1/orders/" + id).<String>json("$.status")).isEqualTo("DRAFT");
        assertThat(stock(small)).isEqualTo(List.of(10, 0, 10));
        assertThat(owner.get("/api/v1/customers/" + customerId).<Integer>json("$.orderCount")).isZero();
    }

    @Test
    void anOrderNeedsAnAddressToBeConfirmed() {
        String noAddress = owner.post("/api/v1/orders", Map.of("customer", Map.of("name", "Walk-in", "phone", "01911000000"),
                "items", List.of(item(small, 1)))).expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + noAddress + "/confirm", null).expectError(400, "validation.failed");

        owner.patch("/api/v1/orders/" + noAddress, Map.of("delivery", Map.of("address", "Shop 3, Gulshan 1"))).expectStatus(200);
        owner.post("/api/v1/orders/" + noAddress + "/confirm", null).expectError(400, "validation.failed");

        owner.patch("/api/v1/orders/" + noAddress, Map.of("delivery", Map.of("areaId", 9015))).expectStatus(200);
        owner.post("/api/v1/orders/" + noAddress + "/confirm", null).expectStatus(200);
    }

    @Test
    void editingAConfirmedOrderMovesOnlyTheDifference() {
        String id = order(small, 3);
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);

        owner.patch("/api/v1/orders/" + id, Map.of("items", List.of(item(small, 5)))).expectStatus(200);
        assertThat(stock(small)).isEqualTo(List.of(10, 5, 5));

        owner.patch("/api/v1/orders/" + id, Map.of("items", List.of(item(small, 1), item(medium, 4)))).expectStatus(200);
        assertThat(stock(small)).isEqualTo(List.of(10, 1, 9));
        assertThat(stock(medium)).isEqualTo(List.of(10, 4, 6));
        assertThat(owner.get("/api/v1/variants/" + small + "/movements").<List<String>>json("$.items[*].type"))
                .containsExactly("RELEASE", "RESERVE", "RESERVE", "OPENING");

        // Too many: refused, and nothing moved.
        owner.patch("/api/v1/orders/" + id, Map.of("items", List.of(item(small, 1), item(medium, 11))))
                .expectError(409, "stock.insufficient");
        assertThat(stock(medium)).isEqualTo(List.of(10, 4, 6));
        assertThat(owner.get("/api/v1/orders/" + id).<List<Integer>>json("$.items[*].quantity")).containsExactly(1, 4);

        owner.patch("/api/v1/orders/" + id, Map.of("customer", Map.of("name", "Other", "phone", "01811111111")))
                .expectError(400, "validation.failed");
        owner.patch("/api/v1/orders/" + id, Map.of("delivery", Map.of("address", " "))).expectError(400, "validation.failed");
        owner.patch("/api/v1/orders/" + id, Map.of("note", "Gift wrap")).expectStatus(200);
    }

    @Test
    void cancellingGivesBackWhatTheOrderHeld() {
        String draft = order(small, 2);
        ApiResponse cancelledDraft = owner.post("/api/v1/orders/" + draft + "/cancel", Map.of("reason", "Customer changed mind"))
                .expectStatus(200);
        assertThat(cancelledDraft.<String>json("$.status")).isEqualTo("CANCELLED");
        assertThat(cancelledDraft.<String>json("$.cancelReason")).isEqualTo("Customer changed mind");

        String confirmed = order(small, 4);
        owner.post("/api/v1/orders/" + confirmed + "/confirm", null).expectStatus(200);
        owner.post("/api/v1/orders/" + confirmed + "/cancel", Map.of("reason", "Wrong size ordered")).expectStatus(200);
        assertThat(stock(small)).isEqualTo(List.of(10, 0, 10));
        assertThat(owner.get("/api/v1/customers/" + customerId).<Integer>json("$.orderCount")).isZero();
        assertThat(events(confirmed)).containsExactly("->DRAFT", "DRAFT->CONFIRMED", "CONFIRMED->CANCELLED");

        owner.post("/api/v1/orders/" + confirmed + "/cancel", Map.of("reason", "Again")).expectError(409, "order.invalid_transition");
        owner.post("/api/v1/orders/" + order(small, 1) + "/cancel", Map.of("reason", "  ")).expectError(400, "validation.failed");
        owner.patch("/api/v1/orders/" + confirmed, Map.of("note", "After cancel")).expectError(409, "order.not_editable");
    }

    @Test
    void aCancelUndoesWhatTheOrderDidEvenAfterTheSettingChanged() {
        String reserved = order(small, 2);
        owner.post("/api/v1/orders/" + reserved + "/confirm", null).expectStatus(200);
        owner.patch("/api/v1/business/settings", Map.of("stockCommitPoint", "CONFIRM")).expectStatus(200);
        String taken = order(small, 3);
        owner.post("/api/v1/orders/" + taken + "/confirm", null).expectStatus(200);
        assertThat(stock(small)).isEqualTo(List.of(7, 2, 5));

        owner.post("/api/v1/orders/" + reserved + "/cancel", Map.of("reason", "Out of town")).expectStatus(200);
        owner.post("/api/v1/orders/" + taken + "/cancel", Map.of("reason", "Duplicate")).expectStatus(200);

        assertThat(stock(small)).isEqualTo(List.of(10, 0, 10));
        assertThat(owner.get("/api/v1/variants/" + small + "/movements").<List<String>>json("$.items[0,1].type"))
                .containsExactly("UNSALE", "RELEASE");
    }

    /** backend-plan.md §6: 20 orders confirmed at once for the last 5 units; exactly 5 succeed. */
    @ParameterizedTest
    @ValueSource(strings = {"SHIP", "CONFIRM"})
    void twentyConfirmationsForFiveUnitsLetExactlyFiveThrough(String commitPoint) throws Exception {
        owner.patch("/api/v1/business/settings", Map.of("stockCommitPoint", commitPoint)).expectStatus(200);
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", small, "kind", "COUNT",
                "quantity", 5)))).expectStatus(201);
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            orders.add(order(small, 1));
        }

        List<Integer> statuses = parallel(orders.stream().map(id -> (Call) () ->
                owner.post("/api/v1/orders/" + id + "/confirm", null).status()).toList());

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(5);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(15);
        assertThat(stock(small)).isEqualTo(commitPoint.equals("SHIP") ? List.of(5, 5, 0) : List.of(0, 0, 0));
        assertThat(systemDatabase.jdbc().sql("""
                        select l.on_hand = sum(m.on_hand_delta) and l.reserved = sum(m.reserved_delta)
                        from stock_levels l join stock_movements m on m.variant_id = l.variant_id
                        where l.variant_id = ? group by l.on_hand, l.reserved
                        """)
                .param(UUID.fromString(small)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void twoCancelsAtOnceGiveOneCancel() throws Exception {
        String id = order(small, 2);
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);

        List<String> codes = new CopyOnWriteArrayList<>();
        List<Integer> statuses = parallel(List.of(
                () -> cancel(id, "First", codes),
                () -> cancel(id, "Second", codes)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        // The order row is locked: the second waits, then finds the order cancelled (not a version clash).
        assertThat(codes).containsExactly("order.invalid_transition");
        assertThat(stock(small)).isEqualTo(List.of(10, 0, 10));
        assertThat(events(id)).hasSize(3);
    }

    private int cancel(String id, String reason, List<String> codes) {
        ApiResponse response = owner.post("/api/v1/orders/" + id + "/cancel", Map.of("reason", reason));
        if (response.status() != 200) {
            codes.add(response.json("$.code"));
        }
        return response.status();
    }

    interface Call {
        int status() throws Exception;
    }

    private static List<Integer> parallel(List<Call> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Call call : calls) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call.status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(120, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private String order(String variantId, int quantity) {
        return owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(variantId, quantity))))
                .expectStatus(201).json("$.id");
    }

    /** [onHand, reserved, available] */
    private List<Integer> stock(String variantId) {
        ApiResponse product = owner.get("/api/v1/products/" + productId);
        String path = "$.variants[?(@.id == '" + variantId + "')]";
        return List.of(product.<List<Integer>>json(path + ".onHand").get(0), product.<List<Integer>>json(path + ".reserved").get(0),
                product.<List<Integer>>json(path + ".available").get(0));
    }

    private List<String> events(String orderId) {
        return systemDatabase.jdbc().sql("""
                        select coalesce(from_status, '') || '->' || to_status from order_events
                        where order_id = ? order by created_at, id
                        """)
                .param(UUID.fromString(orderId)).query(String.class).list();
    }

    private static Map<String, Object> item(String variantId, int quantity) {
        return Map.of("variantId", variantId, "quantity", quantity);
    }
}
