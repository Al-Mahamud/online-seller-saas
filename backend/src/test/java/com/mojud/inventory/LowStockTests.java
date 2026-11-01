package com.mojud.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.inventory.system.StockReconciliation;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.idempotency.system.IdempotencyCleanup;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Low-stock alerts, the running-low list and the nightly jobs (implementation-plan.md 4b). */
@IntegrationTest
class LowStockTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    TestClock clock;

    @Autowired
    StockReconciliation reconciliation;

    @Autowired
    IdempotencyCleanup idempotencyCleanup;

    @Autowired
    SystemDatabase systemDatabase;

    ApiClient owner;
    UUID business;
    String black;
    String white;

    @BeforeEach
    void productAtTen() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Alert Shop");
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 1500,
                "openingStock", 10, "options", List.of(Map.of("name", "Color", "values", List.of("Black", "White")))))
                .expectStatus(201);
        black = product.json("$.variants[0].id");
        white = product.json("$.variants[1].id");
        owner.patch("/api/v1/variants/" + black, Map.of("lowStockThreshold", 3)).expectStatus(200);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void reachingTheAlertLevelNotifiesOncePerDay() {
        damage(black, 6);
        assertThat(alerts()).isEmpty();

        damage(black, 1);
        damage(black, 1);

        ApiResponse bell = owner.get("/api/v1/notifications").expectStatus(200);
        assertThat(bell.<List<String>>json("$.items[*].title")).containsExactly("Cotton Kurti (Black) is running low: 3 left (alert at 3).");
        assertThat(bell.<Integer>json("$.items[0].payload.available")).isEqualTo(3);

        clock.advance(Duration.ofDays(1));
        damage(black, 1);
        assertThat(alerts()).hasSize(2);
    }

    @Test
    void variantsWithoutTheirOwnLevelUseTheBusinessDefault() {
        owner.patch("/api/v1/business/settings", Map.of("lowStockDefault", 8)).expectStatus(200);

        damage(white, 2);

        assertThat(alerts()).hasSize(1);
        assertThat(owner.get("/api/v1/notifications").<Integer>json("$.items[0].payload.threshold")).isEqualTo(8);
    }

    @Test
    void receivingStockNeverAlerts() {
        damage(black, 8);
        clock.advance(Duration.ofDays(1));

        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", black, "kind", "RECEIVE",
                "quantity", 1)))).expectStatus(201);

        assertThat(alerts()).hasSize(1);
    }

    @Test
    void alertsAreForManagersAndCanBeMarkedRead() {
        damage(black, 8);
        ApiClient staff = staff();

        assertThat(staff.get("/api/v1/notifications").<List<Object>>json("$.items")).isEmpty();
        ApiResponse before = owner.get("/api/v1/notifications");
        assertThat(before.<Integer>json("$.unread")).isEqualTo(1);
        assertThat(before.<Boolean>json("$.items[0].read")).isFalse();

        owner.post("/api/v1/notifications/read", Map.of("all", true)).expectStatus(204);

        ApiResponse after = owner.get("/api/v1/notifications");
        assertThat(after.<Integer>json("$.unread")).isZero();
        assertThat(after.<Boolean>json("$.items[0].read")).isTrue();
    }

    @Test
    void titlesFollowTheReadersLanguage() {
        owner.patch("/api/v1/me", Map.of("locale", "bn")).expectStatus(200);

        damage(black, 8);

        assertThat(owner.get("/api/v1/notifications").<String>json("$.items[0].title"))
                .isEqualTo("Cotton Kurti (Black)-এর স্টক কমে গেছে: আর ২টি আছে (সতর্কসীমা ৩)।");
    }

    @Test
    void theRunningLowListShowsSellableVariantsEmptiestFirst() {
        damage(black, 8);
        String other = owner.post("/api/v1/products", Map.of("name", "Archived Thing", "price", 1)).expectStatus(201)
                .json("$.id");
        owner.post("/api/v1/products/" + other + "/archive", null).expectStatus(200);

        ApiResponse low = owner.get("/api/v1/stock/low").expectStatus(200);

        assertThat(low.<List<String>>json("$.items[*].label")).containsExactly("Black");
        assertThat(low.<Integer>json("$.items[0].available")).isEqualTo(2);
        assertThat(low.<Integer>json("$.items[0].threshold")).isEqualTo(3);
    }

    // Nightly jobs.

    @Test
    void reconciliationReportsACacheThatNoLongerMatchesItsLedger() {
        assertThat(reconciliation.run()).noneMatch(m -> m.businessId().equals(business));

        systemDatabase.jdbc().sql("update stock_levels set on_hand = on_hand + 100 where variant_id = ?")
                .param(UUID.fromString(white)).update();

        List<StockReconciliation.Mismatch> found = reconciliation.run().stream()
                .filter(m -> m.businessId().equals(business)).toList();
        assertThat(found).singleElement().satisfies(m -> {
            assertThat(m.variantId()).isEqualTo(UUID.fromString(white));
            assertThat(m.onHand()).isEqualTo(110);
            assertThat(m.ledgerOnHand()).isEqualTo(10);
        });
        // Put it back, so other tests' runs stay clean.
        systemDatabase.jdbc().sql("update stock_levels set on_hand = on_hand - 100 where variant_id = ?")
                .param(UUID.fromString(white)).update();
    }

    @Test
    void idempotencyKeysOlderThanADayAreRemoved() {
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", white, "kind", "RECEIVE",
                "quantity", 1))), Map.of("Idempotency-Key", "old-one")).expectStatus(201);

        idempotencyCleanup.run();
        assertThat(keyCount()).isEqualTo(1);

        clock.advance(Duration.ofDays(1).plusMinutes(1));
        idempotencyCleanup.run();
        assertThat(keyCount()).isZero();
    }

    private long keyCount() {
        return systemDatabase.jdbc().sql("select count(*) from idempotency_keys where business_id = ? and key = 'old-one'")
                .param(business).query(Long.class).single();
    }

    private void damage(String variant, int quantity) {
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", variant, "kind", "DAMAGE",
                "quantity", quantity)))).expectStatus(201);
    }

    private List<Object> alerts() {
        return owner.get("/api/v1/notifications").expectStatus(200).json("$.items[?(@.type == 'stock.low')]");
    }

    private ApiClient staff() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);
        return staff;
    }
}
