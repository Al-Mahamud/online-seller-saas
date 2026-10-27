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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Pack, ship and deliver (implementation-plan.md 8a). */
@IntegrationTest
class FulfilmentTests {

    static final int MIRPUR = 9027;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    ApiClient owner;
    String productId;
    String variant;
    String customerId;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Fulfilment Shop");
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Panjabi", "price", 220000, "openingStock", 10))
                .expectStatus(201);
        productId = product.json("$.id");
        variant = product.json("$.variants[0].id");
        customerId = owner.post("/api/v1/customers", Map.of("name", "Karim", "phone", "01819876543",
                "address", "Road 7, Block C", "areaId", MIRPUR)).expectStatus(201).json("$.id");
    }

    @Test
    void shippingTakesReservedStock() {
        String id = confirmed(3);
        assertThat(stock()).isEqualTo(List.of(10, 3));

        owner.post("/api/v1/orders/" + id + "/pack", null).expectStatus(200);
        ApiResponse shipped = owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao", "trackingNo", "PTH-77812",
                "courierCharge", 6000)).expectStatus(200);

        assertThat(stock()).isEqualTo(List.of(7, 0));
        assertThat(owner.get("/api/v1/variants/" + variant + "/movements").<String>json("$.items[0].type")).isEqualTo("SHIP");
        assertThat(shipped.<String>json("$.status")).isEqualTo("SHIPPED");
        assertThat(shipped.<String>json("$.stockState")).isEqualTo("DEDUCTED");
        assertThat(shipped.<String>json("$.shipments[0].courier")).isEqualTo("Pathao");
        assertThat(shipped.<String>json("$.shipments[0].trackingNo")).isEqualTo("PTH-77812");
        assertThat(shipped.<Integer>json("$.shipments[0].courierCharge")).isEqualTo(6000);
        assertThat(shipped.<Integer>json("$.shipments[0].codExpected")).isEqualTo(shipped.<Integer>json("$.codAmount"));
        assertThat(shipped.<String>json("$.shipments[0].codStatus")).isEqualTo("WITH_COURIER");
        assertThat(events(id)).containsExactly("->DRAFT", "DRAFT->CONFIRMED", "CONFIRMED->PACKED", "PACKED->SHIPPED");
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("order.shipped");

        ApiResponse delivered = owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200);
        assertThat(delivered.<String>json("$.status")).isEqualTo("DELIVERED");
        assertThat(delivered.<String>json("$.shipments[0].status")).isEqualTo("DELIVERED");
        assertThat(delivered.<String>json("$.deliveredAt")).isNotNull();
        assertThat(stock()).isEqualTo(List.of(7, 0));
    }

    @Test
    void withStockTakenAtConfirmationShippingMovesNothing() {
        owner.patch("/api/v1/business/settings", Map.of("stockCommitPoint", "CONFIRM")).expectStatus(200);
        String id = confirmed(3);
        assertThat(stock()).isEqualTo(List.of(7, 0));

        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Steadfast")).expectStatus(200);

        assertThat(stock()).isEqualTo(List.of(7, 0));
        assertThat(owner.get("/api/v1/variants/" + variant + "/movements").<List<String>>json("$.items[*].type"))
                .containsExactly("SALE", "OPENING");
    }

    @Test
    void anOrderShipsOnce() throws Exception {
        String id = confirmed(2);
        List<Integer> statuses = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String courier : List.of("Pathao", "RedX")) {
                results.add(pool.submit(() -> {
                    start.await();
                    return owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", courier)).status();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(owner.get("/api/v1/orders/" + id).<List<Object>>json("$.shipments")).hasSize(1);
        assertThat(stock()).isEqualTo(List.of(8, 0));
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao")).expectError(409, "order.invalid_transition");
    }

    @Test
    void eachStepOnlyFromItsPlace() {
        String draft = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(1))))
                .expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + draft + "/pack", null).expectError(409, "order.invalid_transition");
        owner.post("/api/v1/orders/" + draft + "/ship", Map.of("courier", "Pathao")).expectError(409, "order.invalid_transition");
        owner.post("/api/v1/orders/" + draft + "/deliver", null).expectError(409, "order.invalid_transition");

        String packed = confirmed(1);
        owner.post("/api/v1/orders/" + packed + "/pack", null).expectStatus(200);
        owner.post("/api/v1/orders/" + packed + "/pack", null).expectError(409, "order.invalid_transition");
        owner.post("/api/v1/orders/" + packed + "/deliver", null).expectError(409, "order.invalid_transition");
        owner.patch("/api/v1/orders/" + packed, Map.of("note", "Late change")).expectError(409, "order.not_editable");

        owner.post("/api/v1/orders/" + packed + "/ship", Map.of("courier", " ")).expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + packed + "/ship", Map.of("courier", "Pathao", "courierCharge", -1))
                .expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + packed + "/ship", Map.of("courier", "Pathao", "trackingNo", "T".repeat(101)))
                .expectError(400, "validation.failed");

        owner.post("/api/v1/orders/" + packed + "/ship", Map.of("courier", "Pathao")).expectStatus(200);
        owner.post("/api/v1/orders/" + packed + "/cancel", Map.of("reason", "Too late")).expectError(409, "order.invalid_transition");
    }

    @Test
    void aPrepaidOrderLeavesTheCourierNothingToCollect() {
        String id = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(1)),
                "advancePaid", 227000)).expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);

        ApiResponse shipped = owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao")).expectStatus(200);

        assertThat(shipped.<Integer>json("$.codAmount")).isZero();
        assertThat(shipped.<Integer>json("$.shipments[0].codExpected")).isZero();
        assertThat(shipped.<String>json("$.shipments[0].codStatus")).isEqualTo("NONE");
    }

    @Test
    void manyOrdersAtOnceEachReportedOnItsOwn() {
        String first = confirmed(1);
        String second = confirmed(1);
        String draft = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(1))))
                .expectStatus(201).json("$.id");

        ApiResponse packed = owner.post("/api/v1/orders/bulk/pack", Map.of("orderIds", List.of(first, draft, second)))
                .expectStatus(200);
        assertThat(packed.<List<Boolean>>json("$[*].ok")).containsExactly(true, false, true);
        assertThat(packed.<List<String>>json("$[*].status")).containsExactly("PACKED", null, "PACKED");
        assertThat(packed.<String>json("$[1].code")).isEqualTo("order.invalid_transition");
        assertThat(packed.<String>json("$[1].message")).isNotBlank();

        ApiResponse shipped = owner.post("/api/v1/orders/bulk/ship", Map.of("courier", "Steadfast", "courierCharge", 6000,
                "orders", List.of(Map.of("orderId", first, "trackingNo", "SF-1"),
                        Map.of("orderId", second, "trackingNo", "SF-2", "courierCharge", 12000)))).expectStatus(200);
        assertThat(shipped.<List<Boolean>>json("$[*].ok")).containsExactly(true, true);
        assertThat(owner.get("/api/v1/orders/" + first).<Integer>json("$.shipments[0].courierCharge")).isEqualTo(6000);
        assertThat(owner.get("/api/v1/orders/" + second).<Integer>json("$.shipments[0].courierCharge")).isEqualTo(12000);
        assertThat(owner.get("/api/v1/orders/" + second).<String>json("$.shipments[0].trackingNo")).isEqualTo("SF-2");

        owner.post("/api/v1/orders/bulk/pack", Map.of("orderIds", List.of())).expectError(400, "validation.failed");
    }

    @Test
    void courierNamesComeFromWhatTheBusinessUses() {
        assertThat(owner.get("/api/v1/shipments/couriers").<List<String>>json("$")).startsWith("Pathao", "Steadfast");

        for (String courier : List.of("Rider Rafiq", "Rider Rafiq", "redx")) {
            owner.post("/api/v1/orders/" + confirmed(1) + "/ship", Map.of("courier", courier)).expectStatus(200);
        }

        List<String> names = owner.get("/api/v1/shipments/couriers").json("$");
        assertThat(names).startsWith("Rider Rafiq", "redx", "Pathao");
        assertThat(names).doesNotContain("RedX");
    }

    private String confirmed(int quantity) {
        String id = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(quantity))))
                .expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);
        return id;
    }

    /** [onHand, reserved] */
    private List<Integer> stock() {
        ApiResponse product = owner.get("/api/v1/products/" + productId);
        return List.of(product.json("$.variants[0].onHand"), product.json("$.variants[0].reserved"));
    }

    private List<String> events(String orderId) {
        return systemDatabase.jdbc().sql("""
                        select coalesce(from_status, '') || '->' || to_status from order_events
                        where order_id = ? order by created_at, id
                        """)
                .param(UUID.fromString(orderId)).query(String.class).list();
    }

    private Map<String, Object> item(int quantity) {
        return Map.of("variantId", variant, "quantity", quantity);
    }
}
