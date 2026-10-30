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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Draft orders, totals and numbering (implementation-plan.md 7a). Money in poisha. */
@IntegrationTest
class OrderTests {

    static final int MIRPUR = 9027;
    static final int SYLHET = 36;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    ApiClient owner;
    UUID business;
    String productId;
    String kurtiS;
    String kurtiM;
    String customerId;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Order Shop");
        ApiResponse kurti = owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 120000, "costPrice", 80000,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201);
        productId = kurti.json("$.id");
        kurtiS = kurti.json("$.variants[0].id");
        kurtiM = kurti.json("$.variants[1].id");
        customerId = owner.post("/api/v1/customers", Map.of("name", "Rahima Khatun", "phone", "01712345678",
                "address", "House 4, Road 2", "areaId", MIRPUR)).expectStatus(201).json("$.id");
    }

    @Test
    void totalsAreTheServersWhateverTheClientSends() {
        Map<String, Object> body = new HashMap<>(Map.of("customerId", customerId,
                "items", List.of(item(kurtiS, 2), Map.of("variantId", kurtiM, "quantity", 1, "unitPrice", 50000)),
                "discount", 10000, "advancePaid", 20000));
        body.putAll(Map.of("subtotal", 1, "total", 1, "codAmount", 1, "deliveryCharge", 7000));

        ApiResponse order = owner.post("/api/v1/orders", body).expectStatus(201);

        assertThat(order.<String>json("$.status")).isEqualTo("DRAFT");
        assertThat(order.<String>json("$.numberLabel")).isEqualTo("#1001");
        assertThat(money(order)).isEqualTo(List.of(290000, 10000, 7000, 287000, 20000, 267000));
        assertThat(order.<List<String>>json("$.items[*].variantLabel")).containsExactly("S", "M");
        assertThat(order.<List<Integer>>json("$.items[*].amount")).containsExactly(240000, 50000);
        assertThat(order.<List<Integer>>json("$.items[*].unitCost")).containsExactly(80000, 80000);
        assertThat(order.<String>json("$.delivery.address")).isEqualTo("House 4, Road 2");
        assertThat(order.<String>json("$.delivery.area.nameEn")).isEqualTo("Mirpur");

        // Names and prices were copied: changing the product later leaves the order alone.
        owner.patch("/api/v1/products/" + productId, Map.of("name", "Silk Kurti")).expectStatus(200);
        owner.patch("/api/v1/variants/" + kurtiS, Map.of("price", 999900)).expectStatus(200);
        ApiResponse again = owner.get("/api/v1/orders/" + order.json("$.id"));
        assertThat(again.<String>json("$.items[0].productName")).isEqualTo("Cotton Kurti");
        assertThat(again.<Integer>json("$.items[0].unitPrice")).isEqualTo(120000);
    }

    @Test
    void theDeliveryChargeComesFromTheZoneUnlessGiven() {
        ApiResponse quoted = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1))))
                .expectStatus(201);
        assertThat(quoted.<Integer>json("$.deliveryCharge")).isEqualTo(7000);

        ApiResponse elsewhere = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1)),
                "delivery", Map.of("districtId", SYLHET, "address", "Zindabazar"))).expectStatus(201);
        assertThat(elsewhere.<Integer>json("$.deliveryCharge")).isEqualTo(13000);
        assertThat(elsewhere.<Object>json("$.delivery.area")).isNull();
        assertThat(elsewhere.<String>json("$.delivery.name")).isEqualTo("Rahima Khatun");

        ApiResponse free = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1)),
                "deliveryCharge", 0)).expectStatus(201);
        assertThat(free.<Integer>json("$.deliveryCharge")).isZero();
    }

    @Test
    void aNewCustomerByPhoneIsMadeOnceAndFoundAfterwards() {
        ApiResponse first = owner.post("/api/v1/orders", Map.of("customer", Map.of("name", "Karim", "phone", "01819-876543",
                "address", "Shop 12, New Market", "districtId", 47), "items", List.of(item(kurtiS, 1)))).expectStatus(201);
        ApiResponse second = owner.post("/api/v1/orders", Map.of("customer", Map.of("name", "K.", "phone", "+8801819876543"),
                "delivery", Map.of("name", "Karim's brother", "phone", "01911223344"), "items", List.of(item(kurtiM, 1))))
                .expectStatus(201);

        assertThat(second.<String>json("$.customer.id")).isEqualTo(first.json("$.customer.id"));
        assertThat(second.<String>json("$.customer.name")).isEqualTo("Karim");
        assertThat(second.<String>json("$.delivery.name")).isEqualTo("Karim's brother");
        assertThat(second.<String>json("$.delivery.phone")).isEqualTo("+8801911223344");
        assertThat(second.<String>json("$.delivery.address")).isEqualTo("Shop 12, New Market");
        assertThat(owner.get("/api/v1/customers?phone=01819876543").<List<Object>>json("$.items")).hasSize(1);
    }

    @Test
    void theCountsFollowTheListsFilters() {
        ApiResponse first = create();
        owner.post("/api/v1/orders", Map.of("customer", Map.of("name", "Karim", "phone", "01819876543"),
                "items", List.of(item(kurtiM, 2)))).expectStatus(201);
        owner.post("/api/v1/orders/" + first.json("$.id") + "/cancel", Map.of("reason", "Changed her mind")).expectStatus(200);

        ApiResponse all = owner.get("/api/v1/orders/counts").expectStatus(200);
        assertThat(all.<Integer>json("$.DRAFT")).isEqualTo(1);
        assertThat(all.<Integer>json("$.CANCELLED")).isEqualTo(1);
        assertThat(all.<Integer>json("$.DELIVERED")).isZero();
        assertThat(owner.get("/api/v1/orders/counts?phone=01712-345678").<Integer>json("$.DRAFT")).isZero();
        assertThat(owner.get("/api/v1/orders/counts?phone=01712-345678").<Integer>json("$.CANCELLED")).isEqualTo(1);
        assertThat(owner.get("/api/v1/orders/counts?number=1002").<Integer>json("$.DRAFT")).isEqualTo(1);
        assertThat(owner.get("/api/v1/orders/counts?from=2001-01-01&to=2001-01-02").<Integer>json("$.DRAFT")).isZero();
        assertThat(owner.get("/api/v1/orders/counts?phone=nonsense").<Integer>json("$.DRAFT")).isZero();
    }

    @Test
    void theDetailsCarryTheTimeline() {
        ApiResponse first = create();
        String id = first.json("$.id");
        assertThat(first.<List<String>>json("$.events[*].to")).containsExactly("DRAFT");

        ApiResponse cancelled = owner.post("/api/v1/orders/" + id + "/cancel", Map.of("reason", "Changed her mind")).expectStatus(200);

        assertThat(cancelled.<List<String>>json("$.events[*].to")).containsExactly("DRAFT", "CANCELLED");
        assertThat(cancelled.<String>json("$.events[1].from")).isEqualTo("DRAFT");
        assertThat(cancelled.<String>json("$.events[1].note")).isEqualTo("Changed her mind");
        assertThat(cancelled.<String>json("$.events[1].by")).isNotBlank();
    }

    @Test
    void aResentOrderIsMadeOnce() {
        Map<String, Object> body = Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1)));
        Map<String, String> key = Map.of("Idempotency-Key", "order-form-7f3a");

        ApiResponse first = owner.post("/api/v1/orders", body, key).expectStatus(201);
        ApiResponse again = owner.post("/api/v1/orders", body, key).expectStatus(201);

        assertThat(again.body()).isEqualTo(first.body());
        assertThat(owner.get("/api/v1/orders").<List<Object>>json("$.items")).hasSize(1);
        owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(kurtiS, 2))), key)
                .expectError(409, "idempotency.key_reused");
    }

    @Test
    void fiftyParallelOrdersGetFiftyNumbersWithoutGaps() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(25);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> numbers = new ArrayList<>();
        try {
            for (int i = 0; i < 50; i++) {
                numbers.add(pool.submit(() -> {
                    start.await();
                    return owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1))))
                            .expectStatus(201).<Number>json("$.number").longValue();
                }));
            }
            start.countDown();
            List<Long> got = new ArrayList<>();
            for (Future<Long> number : numbers) {
                got.add(number.get(120, TimeUnit.SECONDS));
            }
            assertThat(got).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1001, 1050).boxed().toList());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theBusinessPrefixGoesOnNewNumbers() {
        create();
        owner.patch("/api/v1/business/settings", Map.of("orderNumberPrefix", "RK-")).expectStatus(200);

        assertThat(create().<String>json("$.numberLabel")).isEqualTo("RK-1002");
        assertThat(owner.get("/api/v1/orders?number=RK-1002").<List<String>>json("$.items[*].numberLabel")).containsExactly("RK-1002");
        assertThat(owner.get("/api/v1/orders?number=%231001").<List<String>>json("$.items[*].numberLabel")).containsExactly("#1001");
    }

    @Test
    void impossibleOrdersAreRefused() {
        List<Object> one = List.of(item(kurtiS, 1));
        bad(Map.of("items", one));
        bad(Map.of("customerId", customerId, "customer", Map.of("name", "X", "phone", "01700000000"), "items", one));
        bad(Map.of("customerId", UUID.randomUUID().toString(), "items", one));
        bad(Map.of("customerId", customerId, "items", List.of()));
        bad(Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1), item(kurtiS, 2))));
        bad(Map.of("customerId", customerId, "items", List.of(item(kurtiS, 0))));
        bad(Map.of("customerId", customerId, "items", List.of(item(UUID.randomUUID().toString(), 1))));
        bad(Map.of("customerId", customerId, "items", one, "discount", 120001));
        bad(Map.of("customerId", customerId, "items", one, "advancePaid", 999999));
        bad(Map.of("customerId", customerId, "items", one, "deliveryCharge", -1));
        bad(Map.of("customerId", customerId, "items", one, "delivery", Map.of("areaId", MIRPUR, "districtId", SYLHET)));
        bad(Map.of("customer", Map.of("name", "X", "phone", "12345"), "items", one));

        owner.post("/api/v1/products/" + productId + "/archive", null).expectStatus(200);
        bad(Map.of("customerId", customerId, "items", one));
        assertThat(owner.get("/api/v1/orders").<List<Object>>json("$.items")).isEmpty();
    }

    @Test
    void draftsAreEditedAndRechecked() {
        ApiResponse order = create();
        String id = order.json("$.id");

        ApiResponse moved = owner.patch("/api/v1/orders/" + id, Map.of("delivery", Map.of("districtId", SYLHET),
                "items", List.of(item(kurtiM, 3)), "discount", 5000)).expectStatus(200);
        assertThat(moved.<List<String>>json("$.items[*].variantLabel")).containsExactly("M");
        assertThat(money(moved)).isEqualTo(List.of(360000, 5000, 13000, 368000, 0, 368000));

        ApiResponse noted = owner.patch("/api/v1/orders/" + id, Map.of("note", "Call before delivery", "deliveryCharge", 10000))
                .expectStatus(200);
        assertThat(noted.<Integer>json("$.deliveryCharge")).isEqualTo(10000);
        assertThat(noted.<Integer>json("$.discount")).isEqualTo(5000);
        assertThat(noted.<List<Integer>>json("$.items[*].quantity")).containsExactly(3);

        owner.patch("/api/v1/orders/" + id, Map.of("note", "Stale", "version", 0)).expectError(409, "resource.modified");
        owner.patch("/api/v1/orders/" + id, Map.of("discount", 999999999)).expectError(400, "validation.failed");

        // Packed orders are not edited (packing comes with Phase 8).
        systemDatabase.jdbc().sql("update orders set status = 'PACKED' where id = ?").param(UUID.fromString(id)).update();
        owner.patch("/api/v1/orders/" + id, Map.of("note", "Too late")).expectError(409, "order.not_editable");
    }

    @Test
    void theListFiltersByStatusDayPhoneAndNumber() {
        ApiResponse first = create();
        ApiResponse second = owner.post("/api/v1/orders", Map.of("customer", Map.of("name", "Karim", "phone", "01819876543"),
                "items", List.of(item(kurtiM, 2)))).expectStatus(201);
        String today = systemDatabase.jdbc().sql("select (now() at time zone 'Asia/Dhaka')::date::text").query(String.class).single();

        assertThat(ids("")).containsExactly(second.<String>json("$.id"), first.<String>json("$.id"));
        assertThat(ids("?status=DRAFT&from=" + today + "&to=" + today)).hasSize(2);
        assertThat(ids("?status=CONFIRMED,CANCELLED")).isEmpty();
        assertThat(ids("?from=2001-01-01&to=2001-01-02")).isEmpty();
        assertThat(ids("?phone=01712-345678")).containsExactly(first.<String>json("$.id"));
        assertThat(ids("?phone=%2B8801819876543")).containsExactly(second.<String>json("$.id"));
        assertThat(ids("?phone=nonsense")).isEmpty();
        assertThat(ids("?number=1002")).containsExactly(second.<String>json("$.id"));
        assertThat(owner.get("/api/v1/orders?limit=1").<Integer>json("$.items[0].itemCount")).isEqualTo(1);
        ApiResponse page = owner.get("/api/v1/orders?limit=1");
        assertThat(owner.get("/api/v1/orders?limit=1&cursor=" + page.json("$.nextCursor")).<List<String>>json("$.items[*].id"))
                .containsExactly(first.<String>json("$.id"));
    }

    @Test
    void creationIsRecordedAndStaffDoNotSeeCost() {
        ApiResponse order = create();
        UUID id = UUID.fromString(order.json("$.id"));

        assertThat(systemDatabase.jdbc().sql("select coalesce(from_status, '-') || '>' || to_status from order_events where order_id = ?")
                .param(id).query(String.class).list()).containsExactly("->DRAFT");
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("order.created");

        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);
        assertThat(staff.get("/api/v1/orders/" + id).<List<Object>>json("$.items[*].unitCost")).containsOnlyNulls();
        staff.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1)))).expectStatus(201);
    }

    private ApiResponse create() {
        return owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(item(kurtiS, 1))))
                .expectStatus(201);
    }

    private void bad(Map<String, Object> body) {
        owner.post("/api/v1/orders", body).expectError(400, "validation.failed");
    }

    private List<String> ids(String query) {
        return owner.get("/api/v1/orders" + query).expectStatus(200).json("$.items[*].id");
    }

    /** [subtotal, discount, deliveryCharge, total, advancePaid, codAmount] */
    private static List<Integer> money(ApiResponse order) {
        return List.of(order.json("$.subtotal"), order.json("$.discount"), order.json("$.deliveryCharge"),
                order.json("$.total"), order.json("$.advancePaid"), order.json("$.codAmount"));
    }

    private static Map<String, Object> item(String variantId, int quantity) {
        return Map.of("variantId", variantId, "quantity", quantity);
    }
}
