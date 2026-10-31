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
 * The "Today" dashboard (implementation-plan.md 9a) on a fixture day whose numbers are worked out
 * by hand below. Kurti ৳1,500 (cost ৳900), Wallet ৳950 (cost ৳600), Scarf ৳500 (no cost); all to
 * Mirpur, ৳70 delivery. Money in poisha.
 */
@IntegrationTest
class DashboardTests {

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

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Dashboard Shop");
        kurti = variant("Kurti", 150000, 90000L, 50);
        wallet = variant("Wallet", 95000, 60000L, 50);
        scarf = variant("Scarf", 50000, null, 5);
        customerId = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678",
                "address", "House 4, Road 2", "areaId", MIRPUR)).expectStatus(201).json("$.id");
    }

    @Test
    void aDayWithEveryKindOfOrderAddsUp() {
        // O1: 2 Kurti, ৳100 off: total 297000. Delivered, courier collected all.
        String o1 = order(Map.of("discount", 10000), item(kurti, 2));
        shipAndDeliver(o1, null);
        // O2: 1 Wallet, total 102000. On the road.
        ship(order(Map.of(), item(wallet, 1)));
        // O3: confirmed, then cancelled: not a sale.
        String o3 = order(Map.of(), item(kurti, 1));
        confirm(o3);
        owner.post("/api/v1/orders/" + o3 + "/cancel", Map.of("reason", "Changed mind")).expectStatus(200);
        // O4: a draft.
        order(Map.of(), item(wallet, 1));
        // O5: 1 Kurti + 1 Wallet, total 252000. Failed delivery: Kurti restocked, Wallet written off.
        String o5 = order(Map.of(), item(kurti, 1), item(wallet, 1));
        ship(o5);
        ApiResponse o5Order = owner.get("/api/v1/orders/" + o5);
        owner.post("/api/v1/orders/" + o5 + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Not home", "items", List.of(
                back(itemId(o5Order, "Kurti"), "RESTOCK"), back(itemId(o5Order, "Wallet"), "WRITE_OFF")))).expectStatus(201);
        // O6: 2 Scarf + 1 Kurti, total 257000, ৳500 bKash advance; delivered (207000 collected);
        // then one Scarf back and ৳500 refunded.
        String o6 = order(Map.of("advancePaid", 50000, "advanceMethod", "BKASH"), item(scarf, 2), item(kurti, 1));
        shipAndDeliver(o6, null);
        owner.post("/api/v1/orders/" + o6 + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Wrong colour",
                "items", List.of(back(itemId(owner.get("/api/v1/orders/" + o6), "Scarf"), 1, "RESTOCK")),
                "refund", Map.of("amount", 50000, "method", "CASH"))).expectStatus(201);
        // O7: 1 Wallet confirmed yesterday: still open, but not today's sale.
        String o7 = order(Map.of(), item(wallet, 1));
        confirm(o7);
        systemDatabase.jdbc().sql("update orders set confirmed_at = ? where id = ?")
                .params(Timestamp.from(Instant.now().minus(Duration.ofDays(1))), UUID.fromString(o7)).update();

        ApiResponse today = owner.get("/api/v1/dashboard/today").expectStatus(200);

        // Confirmed today and not cancelled: O1, O2, O5, O6; what they owe after returns:
        // 297000 + 102000 + 0 + (257000 - 50000).
        assertThat(today.<Integer>json("$.confirmed.orders")).isEqualTo(4);
        assertThat(today.<Integer>json("$.confirmed.value")).isEqualTo(606000);
        assertThat(today.<Integer>json("$.cancelled")).isEqualTo(1);
        assertThat(today.<Map<String, Integer>>json("$.byStatus")).isEqualTo(Map.of("DRAFT", 1, "CONFIRMED", 1, "PACKED", 0,
                "SHIPPED", 1, "DELIVERED", 2, "RETURNED", 1, "CANCELLED", 1));
        assertThat(today.<Integer>json("$.shipped")).isEqualTo(4);
        assertThat(today.<Integer>json("$.delivered")).isEqualTo(2);
        // On the road: O2. Collected, not paid out: O1 297000 + O6 207000.
        assertThat(today.<Map<String, Integer>>json("$.cod")).isEqualTo(Map.of("inTransit", 102000, "withCourier", 504000,
                "outstanding", 606000));
        // Scarf: 5 in, 2 out, 1 back = 4, at or below the default alert level of 5.
        assertThat(today.<Integer>json("$.lowStock")).isEqualTo(1);
        // Kept: Kurti 2 (O1) + 0 (O5, back) + 1 (O6); Wallet 1 (O2) + 0 (O5); Scarf 2 - 1 (O6).
        assertThat(today.<List<String>>json("$.topProducts[*].productName")).containsExactly("Kurti", "Wallet", "Scarf");
        assertThat(today.<List<Integer>>json("$.topProducts[*].quantity")).containsExactly(3, 1, 1);
        assertThat(today.<List<Integer>>json("$.topProducts[*].value")).containsExactly(450000, 95000, 50000);
        // Revenue: goods after discount less returns: 290000 + 95000 + 0 + (250000 - 50000) = 585000.
        // Cost: O1 2 x 90000, O2 60000, O5 the written-off Wallet 60000 (the Kurti came back), O6 the
        // Kurti 90000 (Scarf has no cost) = 390000.
        assertThat(today.<Map<String, Object>>json("$.profit")).isEqualTo(Map.of("revenue", 585000, "cost", 390000,
                "grossProfit", 195000, "costMissing", true));

        String dayBefore = LocalDate.parse(today.<String>json("$.date")).minusDays(1).toString();
        ApiResponse yesterday = owner.get("/api/v1/dashboard/today?date=" + dayBefore).expectStatus(200);
        assertThat(yesterday.<Integer>json("$.confirmed.orders")).isEqualTo(1);
        assertThat(yesterday.<Integer>json("$.confirmed.value")).isEqualTo(102000);
        owner.get("/api/v1/dashboard/today?date=2999-01-01").expectError(400, "validation.failed");
    }

    @Test
    void staffSeeTheDayWithoutProfit() {
        ship(order(Map.of(), item(kurti, 1)));
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);

        ApiResponse today = staff.get("/api/v1/dashboard/today").expectStatus(200);

        assertThat(today.<Integer>json("$.confirmed.orders")).isEqualTo(1);
        assertThat(today.<Object>json("$.profit")).isNull();
        assertThat(owner.get("/api/v1/dashboard/today").<Map<String, Object>>json("$.profit")).containsEntry("grossProfit", 60000);
    }

    @Test
    void aNewBusinessHasAnEmptyDay() {
        ApiResponse today = owner.get("/api/v1/dashboard/today").expectStatus(200);

        assertThat(today.<Integer>json("$.confirmed.orders")).isZero();
        assertThat(today.<List<Object>>json("$.topProducts")).isEmpty();
        assertThat(today.<Map<String, Object>>json("$.profit")).isEqualTo(Map.of("revenue", 0, "cost", 0, "grossProfit", 0,
                "costMissing", false));
    }

    @Test
    void theOverviewSaysWhatNeedsAttentionAndHowCouriersDo() {
        ApiResponse empty = owner.get("/api/v1/dashboard/overview").expectStatus(200);
        assertThat(empty.<Boolean>json("$.hasProducts")).isTrue();
        assertThat(empty.<Boolean>json("$.hasOrders")).isFalse();
        assertThat(empty.<List<Object>>json("$.couriers")).isEmpty();
        assertThat(empty.<Map<String, Integer>>json("$.returnReasons")).isEqualTo(Map.of("FAILED_DELIVERY", 0,
                "PARTIAL_DELIVERY", 0, "CUSTOMER_RETURN", 0));

        // On the way: one just shipped, one six days ago (late), one 40 days ago (late, outside the window).
        ship(order(Map.of(), item(wallet, 1)));
        String sixDays = order(Map.of(), item(wallet, 1));
        ship(sixDays);
        shippedDaysAgo(sixDays, 6);
        String fortyDays = order(Map.of(), item(wallet, 1));
        ship(fortyDays);
        shippedDaysAgo(fortyDays, 40);
        // Pathao delivered: COD collected eight days ago is stuck, one collected today isn't.
        String stuck = order(Map.of(), item(kurti, 1));
        shipAndDeliver(stuck, null);
        systemDatabase.jdbc().sql("update shipments set delivered_at = ? where order_id = ?")
                .params(daysAgo(8), UUID.fromString(stuck)).update();
        shipAndDeliver(order(Map.of(), item(kurti, 1)), null);
        // Steadfast: one came back, with a customer's return on a delivered one.
        String failed = order(Map.of(), item(kurti, 1));
        confirm(failed);
        owner.post("/api/v1/orders/" + failed + "/ship", Map.of("courier", "Steadfast")).expectStatus(200);
        owner.post("/api/v1/orders/" + failed + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Not home", "items",
                List.of(back(itemId(owner.get("/api/v1/orders/" + failed), "Kurti"), "RESTOCK")))).expectStatus(201);
        String kept = order(Map.of(), item(kurti, 1));
        confirm(kept);
        owner.post("/api/v1/orders/" + kept + "/ship", Map.of("courier", "Steadfast")).expectStatus(200);
        owner.post("/api/v1/orders/" + kept + "/deliver", Map.of()).expectStatus(200);
        owner.post("/api/v1/orders/" + kept + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Wrong colour",
                "items", List.of(back(itemId(owner.get("/api/v1/orders/" + kept), "Kurti"), "RESTOCK")))).expectStatus(201);
        // A return on a parcel shipped outside the window isn't counted, nor is that parcel.
        String old = order(Map.of(), item(kurti, 1));
        shipAndDeliver(old, null);
        owner.post("/api/v1/orders/" + old + "/returns", Map.of("kind", "CUSTOMER_RETURN", "reason", "Too small",
                "items", List.of(back(itemId(owner.get("/api/v1/orders/" + old), "Kurti"), "RESTOCK")))).expectStatus(201);
        shippedDaysAgo(old, 31);

        ApiResponse overview = owner.get("/api/v1/dashboard/overview").expectStatus(200);

        assertThat(overview.<Boolean>json("$.hasOrders")).isTrue();
        assertThat(overview.<Integer>json("$.lateParcels")).isEqualTo(2);
        assertThat(overview.<List<String>>json("$.stuckCod[*].courier")).containsExactly("Pathao");
        // The Kurti at ৳1,500 and ৳70 delivery.
        assertThat(overview.<List<Integer>>json("$.stuckCod[*].amount")).containsExactly(157000);
        // Pathao: three on the way, two delivered in the window. Steadfast: one delivered, one back.
        assertThat(overview.<List<String>>json("$.couriers[*].courier")).containsExactly("Pathao", "Steadfast");
        assertThat(overview.<List<Integer>>json("$.couriers[*].onTheWay")).containsExactly(3, 0);
        assertThat(overview.<List<Integer>>json("$.couriers[*].delivered")).containsExactly(2, 1);
        assertThat(overview.<List<Integer>>json("$.couriers[*].returned")).containsExactly(0, 1);
        assertThat(overview.<Map<String, Integer>>json("$.returnReasons")).isEqualTo(Map.of("FAILED_DELIVERY", 1,
                "PARTIAL_DELIVERY", 0, "CUSTOMER_RETURN", 1));
    }

    private void shippedDaysAgo(String orderId, int days) {
        systemDatabase.jdbc().sql("update shipments set shipped_at = ? where order_id = ?")
                .params(daysAgo(days), UUID.fromString(orderId)).update();
    }

    private static Timestamp daysAgo(int days) {
        return Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
    }

    private String variant(String name, long price, Long cost, int stock) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "price", price, "openingStock", stock));
        if (cost != null) {
            body.put("costPrice", cost);
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
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao")).expectStatus(200);
    }

    private void shipAndDeliver(String id, Long collected) {
        ship(id);
        owner.post("/api/v1/orders/" + id + "/deliver", collected == null ? Map.of() : Map.of("codCollected", collected))
                .expectStatus(200);
    }

    private static Map<String, Object> item(String variantId, int quantity) {
        return Map.of("variantId", variantId, "quantity", quantity);
    }

    private static String itemId(ApiResponse order, String productName) {
        return order.<List<String>>json("$.items[?(@.productName == '" + productName + "')].id").get(0);
    }

    private static Map<String, Object> back(String orderItemId, String disposition) {
        return back(orderItemId, 1, disposition);
    }

    private static Map<String, Object> back(String orderItemId, int quantity, String disposition) {
        return Map.of("orderItemId", orderItemId, "quantity", quantity, "disposition", disposition);
    }
}
