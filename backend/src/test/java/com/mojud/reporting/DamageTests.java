package com.mojud.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.membership.MembershipRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The damaged products page: Kurti S and M at ৳1,000 (cost ৳600) and a Scarf with no cost price.
 * Two Kurti S are stained on the shelf, a Scarf is lost, then a parcel comes back with one Kurti S
 * torn. Money in poisha.
 */
@IntegrationTest
class DamageTests {

    static final int MIRPUR = 9027;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    TransactionTemplate transactions;

    ApiClient owner;
    UUID businessId;
    String small;
    String medium;
    String scarf;
    String orderId;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        businessId = TestBusinesses.create(owner, "Damage Shop");
        ApiResponse kurti = owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100000, "costPrice", 60000,
                "openingStock", 10, "options", List.of(Map.of("name", "Size", "values", List.of("S", "M")))))
                .expectStatus(201);
        small = kurti.json("$.variants[0].id");
        medium = kurti.json("$.variants[1].id");
        scarf = owner.post("/api/v1/products", Map.of("name", "Scarf", "price", 50000, "openingStock", 5))
                .expectStatus(201).json("$.variants[0].id");

        damage(small, 2, "STAIN", "Rain came in");
        damage(scarf, 1, "LOST", null);
        String customerId = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678",
                "address", "House 4, Road 2", "areaId", MIRPUR)).expectStatus(201).json("$.id");
        orderId = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(
                Map.of("variantId", small, "quantity", 2), Map.of("variantId", medium, "quantity", 1))))
                .expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + orderId + "/confirm", null).expectStatus(200);
        owner.post("/api/v1/orders/" + orderId + "/ship", Map.of("courier", "Pathao")).expectStatus(200);
        ApiResponse order = owner.get("/api/v1/orders/" + orderId);
        String smallItem = order.<List<String>>json("$.items[?(@.variantLabel == 'S')].id").get(0);
        String mediumItem = order.<List<String>>json("$.items[?(@.variantLabel == 'M')].id").get(0);
        owner.post("/api/v1/orders/" + orderId + "/returns", Map.of("kind", "FAILED_DELIVERY", "reason", "Torn seam",
                "items", List.of(back(smallItem, 1, "WRITE_OFF"), back(smallItem, 1, "RESTOCK"),
                        back(mediumItem, 1, "RESTOCK")))).expectStatus(201);
    }

    @Test
    void shelfDamageAndDamagedReturnsAreListedNewestFirstWithTheirLoss() {
        ApiResponse list = owner.get("/api/v1/damages").expectStatus(200);

        assertThat(list.<Integer>json("$.totalItems")).isEqualTo(3);
        assertThat(list.<List<String>>json("$.items[*].source")).containsExactly("RETURN", "SHELF", "SHELF");

        Map<String, Object> returned = list.json("$.items[0]");
        assertThat(returned).containsEntry("variantId", small).containsEntry("productName", "Kurti")
                .containsEntry("variantLabel", "S").containsEntry("quantity", 1).containsEntry("note", "Torn seam")
                .containsEntry("orderId", orderId).containsEntry("orderNumber", "#1001")
                .containsEntry("recordedBy", "Test Seller").containsEntry("unitCost", 60000).containsEntry("loss", 60000);
        assertThat(returned.get("reason")).isNull();

        Map<String, Object> lost = list.json("$.items[1]");
        assertThat(lost).containsEntry("variantId", scarf).containsEntry("reason", "LOST").containsEntry("quantity", 1);
        assertThat(lost.get("unitCost")).isNull();
        assertThat(lost.get("loss")).isNull();
        assertThat(lost.get("orderId")).isNull();

        Map<String, Object> stained = list.json("$.items[2]");
        assertThat(stained).containsEntry("reason", "STAIN").containsEntry("note", "Rain came in")
                .containsEntry("quantity", 2).containsEntry("loss", 120000).containsEntry("recordedBy", "Test Seller");
    }

    @Test
    void theSummarySplitsShelfFromReturnsAndRanksProductsByLoss() {
        ApiResponse summary = owner.get("/api/v1/damages/summary").expectStatus(200);

        assertThat(summary.<Integer>json("$.quantity")).isEqualTo(4);
        assertThat(summary.<Integer>json("$.shelfQuantity")).isEqualTo(3);
        assertThat(summary.<Integer>json("$.returnQuantity")).isEqualTo(1);
        assertThat(summary.<Integer>json("$.quantityBefore")).isZero();
        assertThat(summary.<Integer>json("$.loss")).isEqualTo(180000);
        assertThat(summary.<Integer>json("$.withoutCost")).isEqualTo(1);
        assertThat(summary.<List<Map<String, Object>>>json("$.top")).singleElement().satisfies(top ->
                assertThat(top).containsEntry("name", "Kurti").containsEntry("quantity", 3).containsEntry("loss", 180000));
    }

    @Test
    void theListFiltersBySourceAndSearch() {
        assertThat(owner.get("/api/v1/damages?source=shelf").<List<String>>json("$.items[*].source"))
                .containsExactly("SHELF", "SHELF");
        assertThat(owner.get("/api/v1/damages?source=RETURN").<Integer>json("$.totalItems")).isEqualTo(1);
        assertThat(owner.get("/api/v1/damages?q=scarf").<List<String>>json("$.items[*].variantId")).containsExactly(scarf);
        assertThat(owner.get("/api/v1/damages?q=nothing-like-it").<Integer>json("$.totalItems")).isZero();
        assertThat(owner.get("/api/v1/damages/summary?q=kurti").<Integer>json("$.quantity")).isEqualTo(3);
        assertThat(owner.get("/api/v1/damages?size=1&page=2").<Integer>json("$.items[0].quantity")).isEqualTo(1);

        owner.get("/api/v1/damages?source=attic").expectError(400, "validation.failed");
    }

    @Test
    void anotherPeriodHasNothingAndTheRangeIsChecked() {
        String today = owner.get("/api/v1/dashboard/today").json("$.date");
        String lastWeek = LocalDate.parse(today).minusDays(7).toString();

        assertThat(owner.get("/api/v1/damages?from=" + lastWeek + "&to=" + lastWeek).<Integer>json("$.totalItems")).isZero();
        owner.get("/api/v1/damages?from=" + today + "&to=" + lastWeek).expectError(400, "validation.failed");
        owner.get("/api/v1/damages/summary?to=" + LocalDate.parse(today).plusDays(1)).expectError(400, "validation.failed");
    }

    @Test
    void staffSeeWhatWasDamagedButNoMoney() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.signUp(staff, messages);
        TestBusinesses.addMember(memberships, transactions, businessId, TestBusinesses.userId(staff), BusinessRole.STAFF);

        ApiResponse list = staff.get("/api/v1/damages").expectStatus(200);
        assertThat(list.<Integer>json("$.totalItems")).isEqualTo(3);
        assertThat(list.<List<Object>>json("$.items[*].loss")).containsOnlyNulls();
        assertThat(list.<List<Object>>json("$.items[*].unitCost")).containsOnlyNulls();

        ApiResponse summary = staff.get("/api/v1/damages/summary").expectStatus(200);
        assertThat(summary.<Integer>json("$.quantity")).isEqualTo(4);
        assertThat(summary.<Object>json("$.loss")).isNull();
        assertThat(summary.<Object>json("$.top")).isNull();
        assertThat(summary.<Object>json("$.withoutCost")).isNull();

        // Staff record damage too.
        staff.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", medium, "kind", "DAMAGE",
                "quantity", 1)), "reason", "BROKEN")).expectStatus(201);
        assertThat(staff.get("/api/v1/damages").<String>json("$.items[0].reason")).isEqualTo("BROKEN");
    }

    @Test
    void aReasonIsKeptOnDamageOnly() {
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(
                Map.of("variantId", medium, "kind", "RECEIVE", "quantity", 3),
                Map.of("variantId", scarf, "kind", "DAMAGE", "quantity", 1)), "reason", "EXPIRED")).expectStatus(201);

        List<String> reasons = systemDatabase.jdbc().sql("""
                        select type || ':' || coalesce(reason, '-') from stock_movements
                        where business_id = ? and type in ('RECEIVE', 'DAMAGE') order by created_at, type""")
                .param(businessId).query(String.class).list();
        assertThat(reasons).containsExactlyInAnyOrder("DAMAGE:STAIN", "DAMAGE:LOST", "DAMAGE:EXPIRED", "RECEIVE:-");
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", scarf, "kind", "DAMAGE",
                "quantity", 1)), "reason", "MICE")).expectStatus(400);
    }

    private static Map<String, Object> back(String orderItemId, int quantity, String disposition) {
        return Map.of("orderItemId", orderItemId, "quantity", quantity, "disposition", disposition);
    }

    private void damage(String variantId, int quantity, String reason, String note) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("lines", List.of(Map.of("variantId", variantId,
                "kind", "DAMAGE", "quantity", quantity)), "reason", reason));
        if (note != null) {
            body.put("note", note);
        }
        owner.post("/api/v1/stock/adjustments", body).expectStatus(201);
    }
}
