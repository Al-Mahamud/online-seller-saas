package com.mojud.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Sales and profit reports (implementation-plan.md 9b) on the dashboard's fixture day plus one order
 * the day before; every figure is worked out by hand below. Kurti ৳1,500 (cost ৳900, category
 * Women > Kurti), Wallet ৳950 (cost ৳600, Accessories), Scarf ৳500 (no cost, no category); ৳70
 * delivery; every courier charges the business ৳60. Money in poisha.
 */
@IntegrationTest
class ReportTests {

    static final int MIRPUR = 9027;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    ApiClient owner;
    String kurti;
    String wallet;
    String scarf;
    String customerId;
    String today;
    String yesterday;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Report Shop");
        String women = owner.post("/api/v1/categories", Map.of("name", "Women")).expectStatus(201).json("$.id");
        String kurtis = owner.post("/api/v1/categories", Map.of("name", "Kurti", "parentId", women)).expectStatus(201).json("$.id");
        String accessories = owner.post("/api/v1/categories", Map.of("name", "Accessories")).expectStatus(201).json("$.id");
        kurti = variant("Kurti", 150000, 90000L, kurtis);
        wallet = variant("Wallet", 95000, 60000L, accessories);
        scarf = variant("Scarf", 50000, null, null);
        customerId = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678",
                "address", "House 4, Road 2", "areaId", MIRPUR)).expectStatus(201).json("$.id");
        today = owner.get("/api/v1/dashboard/today").json("$.date");
        yesterday = LocalDate.parse(today).minusDays(1).toString();
        fixture();
    }

    /** The dashboard's day (O1-O6) and O7, a Wallet confirmed and shipped yesterday. */
    private void fixture() {
        shipAndDeliver(order(Map.of("discount", 10000), item(kurti, 2)));
        ship(order(Map.of(), item(wallet, 1)));
        String cancelled = order(Map.of(), item(kurti, 1));
        confirm(cancelled);
        owner.post("/api/v1/orders/" + cancelled + "/cancel", Map.of("reason", "Changed mind")).expectStatus(200);
        order(Map.of(), item(wallet, 1));
        String failed = order(Map.of(), item(kurti, 1), item(wallet, 1));
        ship(failed);
        ApiResponse failedOrder = owner.get("/api/v1/orders/" + failed);
        owner.post("/api/v1/orders/" + failed + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Not home", "items", List.of(
                back(itemId(failedOrder, "Kurti"), 1, "RESTOCK"), back(itemId(failedOrder, "Wallet"), 1, "WRITE_OFF"))))
                .expectStatus(201);
        String returned = order(Map.of("advancePaid", 50000, "advanceMethod", "BKASH"), item(scarf, 2), item(kurti, 1));
        shipAndDeliver(returned);
        owner.post("/api/v1/orders/" + returned + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Wrong colour",
                "items", List.of(back(itemId(owner.get("/api/v1/orders/" + returned), "Scarf"), 1, "RESTOCK")),
                "refund", Map.of("amount", 50000, "method", "CASH"))).expectStatus(201);
        String old = order(Map.of(), item(wallet, 1));
        ship(old);
        systemDatabase.jdbc().sql("update orders set confirmed_at = ? where id = ?")
                .params(Timestamp.from(Instant.now().minus(Duration.ofDays(1))), UUID.fromString(old)).update();
    }

    @Test
    void salesByDay() {
        ApiResponse sales = owner.get("/api/v1/reports/sales?from=" + yesterday + "&to=" + today + "&groupBy=day").expectStatus(200);

        assertThat(sales.<List<String>>json("$.rows[*].key")).containsExactly(yesterday, today);
        // Yesterday: O7, one Wallet: 95000, delivery 7000.
        assertThat(row(sales, 0)).isEqualTo(List.of(1, 1, 95000, 7000));
        // Today: O1, O2, O5, O6. Kept 2 + 1 + 0 + 2; revenue 290000 + 95000 + 0 + 200000; delivery
        // 7000 each except the failed O5.
        assertThat(row(sales, 1)).isEqualTo(List.of(4, 5, 585000, 21000));
        assertThat(sales.<Integer>json("$.quantity")).isEqualTo(6);
        assertThat(sales.<Integer>json("$.value")).isEqualTo(680000);
    }

    @Test
    void salesByProductAndCategory() {
        ApiResponse products = owner.get("/api/v1/reports/sales?from=" + yesterday + "&to=" + today + "&groupBy=PRODUCT")
                .expectStatus(200);
        // Kept: Kurti 2 + 0 + 1; Wallet 1 (O2) + 0 (O5) + 1 (O7); Scarf 2 - 1. O1's ৳100 discount comes off
        // its Kurtis (300000 - 10000), so the products add up to the days' 680000.
        assertThat(products.<List<String>>json("$.rows[*].label")).containsExactly("Kurti", "Wallet", "Scarf");
        assertThat(products.<List<Integer>>json("$.rows[*].quantity")).containsExactly(3, 2, 1);
        assertThat(products.<List<Integer>>json("$.rows[*].value")).containsExactly(440000, 190000, 50000);
        assertThat(products.<Integer>json("$.value")).isEqualTo(680000);

        ApiResponse categories = owner.get("/api/v1/reports/sales?from=" + yesterday + "&to=" + today + "&groupBy=category")
                .expectStatus(200);
        assertThat(categories.<List<String>>json("$.rows[*].label")).containsExactly("Women > Kurti", "Accessories", "Uncategorised");
        assertThat(categories.<List<Integer>>json("$.rows[*].value")).containsExactly(440000, 190000, 50000);

        assertThat(owner.get("/api/v1/reports/sales?from=" + today + "&to=" + today + "&groupBy=product")
                .<List<Integer>>json("$.rows[*].quantity")).containsExactly(3, 1, 1);
    }

    /** A discount over several items is shared by their value, to the paisa: ৳10 over ৳1,500 and ৳950 x 2. */
    @Test
    void aDiscountIsSharedOverItsItems() {
        String id = order(Map.of("discount", 1000), item(kurti, 1), item(wallet, 2));
        confirm(id);
        ApiResponse products = owner.get("/api/v1/reports/sales?from=" + today + "&to=" + today + "&groupBy=product");
        int dayValue = owner.get("/api/v1/reports/sales?from=" + today + "&to=" + today + "&groupBy=day").json("$.value");

        assertThat(products.<Integer>json("$.value")).isEqualTo(dayValue);
        // Kurti 150000 of 340000 takes 441.2 of the 1000 (441 or 442, by which item rounds first); the Wallets the rest.
        assertThat(products.<List<String>>json("$.rows[*].label")).containsExactly("Kurti", "Wallet", "Scarf");
        List<Integer> values = products.json("$.rows[*].value");
        assertThat(values.get(0)).isIn(440000 + 150000 - 441, 440000 + 150000 - 442);
        assertThat(values.get(0) + values.get(1)).isEqualTo(440000 + 150000 + 95000 + 190000 - 1000);
    }

    @Test
    void profitSeparatesGoodsFromDelivery() {
        ApiResponse profit = owner.get("/api/v1/reports/profit?from=" + today + "&to=" + today).expectStatus(200);

        // Revenue as on the dashboard: 585000. Cost of goods kept: O1 2 x 90000 + O2 60000 + O6 90000
        // (the Scarf has no cost) = 330000. Written off: O5's Wallet 60000. Gross: 195000.
        // Delivery: customers owe 3 x 7000 (not the failed O5); couriers charged 4 x 6000. Net: 192000.
        assertThat(profit.<Map<String, Integer>>json("$.profit")).isEqualTo(Map.of("orders", 4, "revenue", 585000,
                "costOfGoods", 330000, "writeOffs", 60000, "grossProfit", 195000, "deliveryCharged", 21000,
                "courierCharges", 24000, "deliveryMargin", -3000, "netProfit", 192000, "itemsWithoutCost", 1));
        assertThat(owner.get("/api/v1/dashboard/today").<Integer>json("$.profit.grossProfit")).isEqualTo(195000);
    }

    @Test
    void rangesAreChecked() {
        ApiResponse defaults = owner.get("/api/v1/reports/sales").expectStatus(200);
        assertThat(defaults.<String>json("$.to")).isEqualTo(today);
        assertThat(defaults.<List<Object>>json("$.rows")).hasSize(30);

        owner.get("/api/v1/reports/sales?from=" + today + "&to=" + yesterday).expectError(400, "validation.failed");
        owner.get("/api/v1/reports/sales?from=2024-01-01&to=" + today).expectError(400, "validation.failed");
        owner.get("/api/v1/reports/sales?to=2999-01-01").expectError(400, "validation.failed");
        owner.get("/api/v1/reports/sales?groupBy=week").expectError(400, "validation.failed");
    }

    @Test
    void staffSeeSalesButNotProfit() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);

        staff.get("/api/v1/reports/sales").expectStatus(200);
        staff.get("/api/v1/reports/profit").expectStatus(403);
        staff.get("/api/v1/exports/orders.csv").expectStatus(403);
    }

    private static List<Integer> row(ApiResponse sales, int index) {
        String row = "$.rows[" + index + "].";
        return List.of(sales.json(row + "orders"), sales.json(row + "quantity"), sales.json(row + "value"),
                sales.json(row + "deliveryCharged"));
    }

    private String variant(String name, long price, Long cost, String categoryId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "price", price, "openingStock", 50));
        if (cost != null) {
            body.put("costPrice", cost);
        }
        if (categoryId != null) {
            body.put("categoryId", categoryId);
        }
        return owner.post("/api/v1/products", body).expectStatus(201).json("$.variants[0].id");
    }

    @SafeVarargs
    private String order(Map<String, Object> extra, Map<String, Object>... items) {
        Map<String, Object> body = new HashMap<>(Map.of("customerId", customerId, "items", List.of(items)));
        body.putAll(extra);
        return owner.post("/api/v1/orders", body).expectStatus(201).json("$.id");
    }

    private void confirm(String id) {
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);
    }

    private void ship(String id) {
        confirm(id);
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao", "courierCharge", 6000)).expectStatus(200);
    }

    private void shipAndDeliver(String id) {
        ship(id);
        owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200);
    }

    private static Map<String, Object> item(String variantId, int quantity) {
        return Map.of("variantId", variantId, "quantity", quantity);
    }

    private static String itemId(ApiResponse order, String productName) {
        return order.<List<String>>json("$.items[?(@.productName == '" + productName + "')].id").get(0);
    }

    private static Map<String, Object> back(String orderItemId, int quantity, String disposition) {
        return Map.of("orderItemId", orderItemId, "quantity", quantity, "disposition", disposition);
    }
}
