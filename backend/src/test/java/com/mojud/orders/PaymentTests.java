package com.mojud.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Payments and COD (implementation-plan.md 8b): the money of an order through its life. One item at
 * ৳1,000 to Mirpur (Inside Dhaka, ৳70): total ৳1,070, i.e. 107000 poisha.
 */
@IntegrationTest
class PaymentTests {

    static final int MIRPUR = 9027;
    static final int SYLHET = 36;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;
    String variant;
    String customerId;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Payment Shop");
        variant = owner.post("/api/v1/products", Map.of("name", "Saree", "price", 100000, "openingStock", 50))
                .expectStatus(201).json("$.variants[0].id");
        customerId = owner.post("/api/v1/customers", Map.of("name", "Nasrin", "phone", "01911223344",
                "address", "Flat 3B, Road 11", "areaId", MIRPUR)).expectStatus(201).json("$.id");
    }

    @Test
    void fullCod() {
        String id = order(Map.of());
        assertThat(money(owner.get("/api/v1/orders/" + id))).isEqualTo(List.of(107000, 0, 107000, 0, 107000, "UNPAID"));

        ApiResponse shipped = ship(id, "Pathao");
        assertThat(shipped.<Integer>json("$.shipments[0].codExpected")).isEqualTo(107000);

        ApiResponse delivered = owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200);
        assertThat(money(delivered)).isEqualTo(List.of(107000, 0, 107000, 107000, 0, "PAID"));
        assertThat(delivered.<Integer>json("$.shipments[0].codCollected")).isEqualTo(107000);
        assertThat(delivered.<String>json("$.shipments[0].codStatus")).isEqualTo("WITH_COURIER");

        ApiResponse settled = owner.post("/api/v1/shipments/settle-cod", Map.of("shipmentIds",
                List.of(delivered.<String>json("$.shipments[0].id")))).expectStatus(200);
        assertThat(settled.<Integer>json("$.amount")).isEqualTo(107000);
        assertThat(settled.<String>json("$.courier")).isEqualTo("Pathao");
        assertThat(owner.get("/api/v1/orders/" + id).<String>json("$.shipments[0].codStatus")).isEqualTo("SETTLED");
    }

    @Test
    void advanceAndCod() {
        String id = order(Map.of("advancePaid", 30000, "advanceMethod", "BKASH", "advanceReference", "8N7A2KX9"));
        ApiResponse more = owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "NAGAD", "amount", 20000,
                "reference", "NG-5521")).expectStatus(201);

        assertThat(money(more)).isEqualTo(List.of(107000, 50000, 57000, 50000, 57000, "PARTIAL"));
        assertThat(more.<List<String>>json("$.payments[*].method")).containsExactly("BKASH", "NAGAD");
        assertThat(more.<List<String>>json("$.payments[*].reference")).containsExactly("8N7A2KX9", "NG-5521");

        assertThat(ship(id, "Steadfast").<Integer>json("$.shipments[0].codExpected")).isEqualTo(57000);
        ApiResponse delivered = owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200);
        assertThat(money(delivered)).isEqualTo(List.of(107000, 50000, 57000, 107000, 0, "PAID"));
        assertThat(owner.get("/api/v1/business/audit-log").<List<String>>json("$.items[*].action")).contains("payment.recorded");
    }

    @Test
    void deliveryChargePaidInAdvance() {
        String id = order(Map.of("delivery", Map.of("districtId", SYLHET)));
        assertThat(owner.get("/api/v1/orders/" + id).<Integer>json("$.deliveryCharge")).isEqualTo(13000);

        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "BKASH", "amount", 13000)).expectStatus(201);

        ApiResponse shipped = ship(id, "Sundarban Courier");
        assertThat(shipped.<Integer>json("$.codAmount")).isEqualTo(100000);
        assertThat(shipped.<Integer>json("$.shipments[0].codExpected")).isEqualTo(100000);
        assertThat(money(owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200)))
                .isEqualTo(List.of(113000, 13000, 100000, 113000, 0, "PAID"));
    }

    @Test
    void collectedLessThanExpected() {
        String id = order(Map.of());
        ship(id, "RedX");

        ApiResponse delivered = owner.post("/api/v1/orders/" + id + "/deliver", Map.of("codCollected", 90000)).expectStatus(200);

        assertThat(money(delivered)).isEqualTo(List.of(107000, 0, 107000, 90000, 17000, "PARTIAL"));
        assertThat(owner.get("/api/v1/orders?size=1").<String>json("$.items[0].paymentStatus")).isEqualTo("PARTIAL");
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "CASH", "amount", 17001))
                .expectError(400, "validation.failed");

        ApiResponse settled = owner.post("/api/v1/shipments/settle-cod", Map.of("shipmentIds",
                List.of(delivered.<String>json("$.shipments[0].id")))).expectStatus(200);
        assertThat(settled.<Integer>json("$.amount")).isEqualTo(90000);

        ApiResponse paidUp = owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "BKASH", "amount", 17000))
                .expectStatus(201);
        // After delivery a payment settles what is owed; the COD the courier carried stays as it was.
        assertThat(money(paidUp)).isEqualTo(List.of(107000, 0, 107000, 107000, 0, "PAID"));
    }

    @Test
    void paymentsOnlyWhenTheyMakeSense() {
        String id = order(Map.of());
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "CASH", "amount", 107001)).expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "CASH", "amount", 0)).expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "PIGEON", "amount", 100)).expectStatus(400);
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "CASH", "amount", 100,
                "receivedAt", "2099-01-01T00:00:00Z")).expectError(400, "validation.failed");
        owner.patch("/api/v1/orders/" + id, Map.of("advancePaid", 100)).expectError(400, "validation.failed");

        // An advance limits how far the total can fall.
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "CASH", "amount", 100000)).expectStatus(201);
        owner.patch("/api/v1/orders/" + id, Map.of("discount", 50000)).expectError(400, "validation.failed");

        ship(id, "Pathao");
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "CASH", "amount", 100)).expectError(409, "order.not_payable");
        owner.post("/api/v1/orders/" + id + "/deliver", Map.of("codCollected", 7001)).expectError(400, "validation.failed");
        owner.post("/api/v1/orders/" + id + "/deliver", Map.of("codCollected", -1)).expectError(400, "validation.failed");

        String cancelled = order(Map.of());
        owner.post("/api/v1/orders/" + cancelled + "/cancel", Map.of("reason", "Duplicate")).expectStatus(200);
        owner.post("/api/v1/orders/" + cancelled + "/payments", Map.of("method", "CASH", "amount", 100))
                .expectError(409, "order.not_payable");
    }

    @Test
    void aResentPaymentIsRecordedOnce() {
        String id = order(Map.of());
        Map<String, Object> payment = Map.of("method", "BKASH", "amount", 20000);
        Map<String, String> key = Map.of("Idempotency-Key", "payment-form-1");

        ApiResponse first = owner.post("/api/v1/orders/" + id + "/payments", payment, key).expectStatus(201);
        ApiResponse again = owner.post("/api/v1/orders/" + id + "/payments", payment, key).expectStatus(201);

        assertThat(again.body()).isEqualTo(first.body());
        assertThat(owner.get("/api/v1/orders/" + id).<Integer>json("$.amountPaid")).isEqualTo(20000);
        owner.post("/api/v1/orders/" + id + "/payments", Map.of("method", "BKASH", "amount", 30000), key)
                .expectError(409, "idempotency.key_reused");
    }

    @Test
    void settlingIsOneCouriersDeliveredCashOnce() {
        String pathaoA = delivered("Pathao");
        String pathaoB = delivered("pathao");
        String redx = delivered("RedX");
        String inTransit = shipmentOf(ship(order(Map.of()), "Pathao"));

        ApiResponse owed = owner.get("/api/v1/shipments?status=DELIVERED&codStatus=WITH_COURIER&courier=Pathao").expectStatus(200);
        assertThat(owed.<List<String>>json("$[*].id")).containsExactlyInAnyOrder(pathaoA, pathaoB);
        assertThat(owed.<List<String>>json("$[*].orderNumberLabel")).allMatch(label -> label.startsWith("#"));

        owner.post("/api/v1/shipments/settle-cod", Map.of("shipmentIds", List.of(pathaoA, redx))).expectError(400, "validation.failed");
        ApiResponse notYet = owner.post("/api/v1/shipments/settle-cod", Map.of("shipmentIds", List.of(pathaoA, inTransit)))
                .expectError(409, "cod.not_settleable");
        assertThat(notYet.<List<String>>json("$.shipmentIds")).containsExactly(inTransit);

        ApiResponse settled = owner.post("/api/v1/shipments/settle-cod", Map.of("shipmentIds", List.of(pathaoA, pathaoB)))
                .expectStatus(200);
        assertThat(settled.<Integer>json("$.shipments")).isEqualTo(2);
        assertThat(settled.<Integer>json("$.amount")).isEqualTo(214000);
        owner.post("/api/v1/shipments/settle-cod", Map.of("shipmentIds", List.of(pathaoA))).expectError(409, "cod.not_settleable");
        assertThat(owner.get("/api/v1/shipments?codStatus=SETTLED").<List<String>>json("$[*].id"))
                .containsExactlyInAnyOrder(pathaoA, pathaoB);
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("cod.settled");
    }

    @Test
    void couriersShowTheirWholeTotalsAndWhatASearchFinds() {
        delivered("Pathao");
        delivered("pathao");
        delivered("RedX");
        ship(order(Map.of()), "RedX");
        ship(order(Map.of()), "Steadfast");

        ApiResponse all = owner.get("/api/v1/shipments/cod/summary").expectStatus(200);
        assertThat(all.<Integer>json("$.owedParcels")).isEqualTo(3);
        assertThat(all.<Integer>json("$.owedAmount")).isEqualTo(321000);
        assertThat(all.<Integer>json("$.inTransitAmount")).isEqualTo(214000);
        assertThat(all.<List<String>>json("$.couriers[*].name")).extracting(String::toLowerCase)
                .containsExactly("pathao", "redx", "steadfast");
        assertThat(all.<Integer>json("$.couriers[0].owedParcels")).isEqualTo(2);
        assertThat(all.<Integer>json("$.couriers[1].inTransitAmount")).isEqualTo(107000);
        assertThat(all.<Integer>json("$.couriers[2].owedParcels")).isZero();

        ApiResponse parcels = owner.get("/api/v1/shipments/cod/parcels?courier=PATHAO&size=1").expectStatus(200);
        assertThat(parcels.<Integer>json("$.totalItems")).isEqualTo(2);
        assertThat(parcels.<Integer>json("$.totalPages")).isEqualTo(2);
        assertThat(parcels.<List<Object>>json("$.items")).hasSize(1);
        String label = parcels.json("$.items[0].orderNumberLabel");
        assertThat(parcels.<String>json("$.items[0].customerName")).isNotBlank();

        // A search narrows what's found, and leaves the totals whole. Bangla digits find the same order.
        String banglaNumber = label.replaceAll("\\D", "").chars().mapToObj(digit -> String.valueOf((char) ('০' + digit - '0')))
                .reduce("", String::concat);
        ApiResponse found = owner.get("/api/v1/shipments/cod/summary?q=" + banglaNumber).expectStatus(200);
        assertThat(found.<Integer>json("$.owedParcels")).isEqualTo(3);
        assertThat(found.<Integer>json("$.couriers[0].matchedParcels")).isEqualTo(1);
        assertThat(found.<Integer>json("$.couriers[0].matchedAmount")).isEqualTo(107000);
        assertThat(owner.get("/api/v1/shipments/cod/parcels?courier=pathao&q=" + banglaNumber).<Integer>json("$.totalItems")).isEqualTo(1);
        // A % in a search is that character, not "anything".
        assertThat(owner.get("/api/v1/shipments/cod/parcels?courier=pathao&q=%25").<Integer>json("$.totalItems")).isZero();
        // Days of delivery, and no match is an empty list.
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")).toString();
        assertThat(owner.get("/api/v1/shipments/cod/parcels?courier=pathao&from=" + today + "&to=" + today).<Integer>json("$.totalItems")).isEqualTo(2);
        assertThat(owner.get("/api/v1/shipments/cod/parcels?courier=pathao&to=2001-01-01").<Integer>json("$.totalItems")).isZero();
    }

    @Test
    void aPayoutCoversEveryPageLessWhatWasLeftOutAndOnlyWhatWasShown() {
        String kept = delivered("Pathao");
        String settled = delivered("pathao");
        String other = delivered("Pathao");
        String redx = delivered("RedX");

        // Parcels changed since it was shown: nothing happens, and it says what's still left out.
        ApiResponse changed = owner.post("/api/v1/shipments/settle-cod/matching", Map.of("courier", "PATHAO",
                "exceptIds", List.of(kept), "expectedParcels", 3, "expectedAmount", 321000)).expectError(409, "cod.list_changed");
        assertThat(changed.<List<String>>json("$.exceptIds")).containsExactly(kept);
        assertThat(owner.get("/api/v1/shipments/cod/summary").<Integer>json("$.couriers[0].owedParcels")).isEqualTo(3);

        ApiResponse done = owner.post("/api/v1/shipments/settle-cod/matching", Map.of("courier", "PATHAO",
                "exceptIds", List.of(kept, redx), "expectedParcels", 2, "expectedAmount", 214000)).expectStatus(200);
        assertThat(done.<Integer>json("$.shipments")).isEqualTo(2);
        assertThat(done.<Integer>json("$.amount")).isEqualTo(214000);
        assertThat(owner.get("/api/v1/shipments?codStatus=SETTLED").<List<String>>json("$[*].id"))
                .containsExactlyInAnyOrder(settled, other);
        assertThat(owner.get("/api/v1/shipments/cod/summary").<Integer>json("$.owedParcels")).isEqualTo(2);

        // A search that finds nothing settles nothing.
        owner.post("/api/v1/shipments/settle-cod/matching", Map.of("courier", "Pathao", "q", "no such thing",
                "expectedParcels", 0, "expectedAmount", 0)).expectError(409, "cod.list_changed");
    }

    @Test
    void nothingCollectedLeavesNothingToSettleAndStaffDoNotSettle() {
        String id = order(Map.of());
        ship(id, "Pathao");
        ApiResponse delivered = owner.post("/api/v1/orders/" + id + "/deliver", Map.of("codCollected", 0)).expectStatus(200);
        assertThat(delivered.<String>json("$.shipments[0].codStatus")).isEqualTo("NONE");
        assertThat(delivered.<String>json("$.paymentStatus")).isEqualTo("UNPAID");

        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);
        staff.get("/api/v1/shipments").expectStatus(200);
        staff.post("/api/v1/shipments/settle-cod", Map.of("shipmentIds", List.of(delivered("RedX")))).expectStatus(403);
        staff.get("/api/v1/shipments/cod/summary").expectStatus(200);
        staff.post("/api/v1/shipments/settle-cod/matching", Map.of("courier", "RedX", "expectedParcels", 1,
                "expectedAmount", 107000)).expectStatus(403);
        staff.post("/api/v1/orders/" + order(Map.of()) + "/payments", Map.of("method", "CASH", "amount", 500)).expectStatus(201);
    }

    private String order(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("customerId", customerId,
                "items", List.of(Map.of("variantId", variant, "quantity", 1))));
        body.putAll(extra);
        return owner.post("/api/v1/orders", body).expectStatus(201).json("$.id");
    }

    private ApiResponse ship(String id, String courier) {
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);
        return owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", courier)).expectStatus(200);
    }

    /** A delivered shipment of a full-COD order; its id. */
    private String delivered(String courier) {
        String id = order(Map.of());
        ship(id, courier);
        return shipmentOf(owner.post("/api/v1/orders/" + id + "/deliver", null).expectStatus(200));
    }

    private static String shipmentOf(ApiResponse order) {
        return order.json("$.shipments[0].id");
    }

    /** [total, advancePaid, codAmount, amountPaid, balanceDue, paymentStatus] */
    private static List<Object> money(ApiResponse order) {
        return List.of(order.json("$.total"), order.json("$.advancePaid"), order.json("$.codAmount"),
                order.json("$.amountPaid"), order.json("$.balanceDue"), order.json("$.paymentStatus"));
    }
}
