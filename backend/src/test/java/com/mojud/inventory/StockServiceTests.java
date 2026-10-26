package com.mojud.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.support.ApiClient;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/** The stock ledger and its cached levels (implementation-plan.md 4a, backend-plan.md §6). */
@IntegrationTest
class StockServiceTests {

    private static final StockService.Reference REF = StockService.Reference.none();

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    StockService stock;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    SystemDatabase systemDatabase;

    UUID business;
    List<UUID> variants;

    @BeforeEach
    void productWithThreeVariants() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Stock Shop");
        List<String> ids = owner.post("/api/v1/products", Map.of("name", "Shirt", "price", 1000,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M", "L"))))).expectStatus(201)
                .json("$.variants[*].id");
        variants = ids.stream().map(UUID::fromString).toList();
    }

    @Test
    void aThousandRandomMovementsLeaveTheCacheEqualToTheLedger() {
        Random random = new Random(42);
        int refused = 0;
        for (int i = 0; i < 1000; i++) {
            UUID variant = variants.get(random.nextInt(variants.size()));
            List<StockService.Line> line = List.of(new StockService.Line(variant, 1 + random.nextInt(5)));
            int op = random.nextInt(8);
            try {
                inBusiness(() -> switch (op) {
                    case 0, 1 -> stock.receive(line, REF);
                    case 2 -> stock.damage(line, REF);
                    case 3 -> stock.reserve(line, REF);
                    case 4 -> stock.release(line, REF);
                    case 5 -> stock.ship(line, REF);
                    case 6 -> stock.sell(line, REF);
                    default -> stock.count(variant, random.nextInt(30), REF);
                });
            } catch (ApiException e) {
                refused++;
            }
        }

        List<Map<String, Object>> mismatches = systemDatabase.jdbc().sql("""
                        select l.variant_id, l.on_hand, l.reserved, sum(m.on_hand_delta) as ledger_on_hand,
                               sum(m.reserved_delta) as ledger_reserved
                        from stock_levels l join stock_movements m on m.variant_id = l.variant_id
                        where l.business_id = ?
                        group by l.variant_id, l.on_hand, l.reserved
                        having l.on_hand <> sum(m.on_hand_delta) or l.reserved <> sum(m.reserved_delta)
                        """)
                .param(business).query().listOfRows();
        long movements = systemDatabase.jdbc().sql("select count(*) from stock_movements where business_id = ?")
                .param(business).query(Long.class).single();

        assertThat(mismatches).isEmpty();
        assertThat(refused).isPositive();
        assertThat(movements).isEqualTo(1000 - refused);
        assertThat(lastAfterMatchesLevel()).isTrue();
    }

    @Test
    void impossibleChangesAreRefusedAndChangeNothing() {
        UUID shirt = variants.get(0);
        inBusiness(() -> stock.receive(List.of(new StockService.Line(shirt, 5)), REF));
        inBusiness(() -> stock.reserve(List.of(new StockService.Line(shirt, 3)), REF));

        assertCode(() -> stock.release(List.of(new StockService.Line(shirt, 4)), REF), "stock.invalid_change");
        assertCode(() -> stock.ship(List.of(new StockService.Line(shirt, 4)), REF), "stock.invalid_change");
        assertCode(() -> stock.damage(List.of(new StockService.Line(shirt, 3)), REF), "stock.insufficient");
        assertCode(() -> stock.reserve(List.of(new StockService.Line(shirt, 3)), REF), "stock.insufficient");
        assertCode(() -> stock.count(shirt, 2, REF), "stock.insufficient");
        assertCode(() -> stock.receive(List.of(new StockService.Line(shirt, 0)), REF), "stock.invalid_change");

        StockLevel level = inBusiness(() -> stock.release(List.of(new StockService.Line(shirt, 1)), REF)).get(0);
        assertThat(level).isEqualTo(new StockLevel(shirt, 5, 2));
    }

    @Test
    void aShortageNamesEveryShortVariantAndChangesNone() {
        inBusiness(() -> stock.receive(List.of(new StockService.Line(variants.get(0), 2),
                new StockService.Line(variants.get(1), 10)), REF));

        assertThatThrownBy(() -> inBusiness(() -> stock.reserve(List.of(new StockService.Line(variants.get(0), 3),
                new StockService.Line(variants.get(1), 4), new StockService.Line(variants.get(2), 1)), REF)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code().code()).isEqualTo("stock.insufficient");
                    assertThat((List<?>) e.details().get("shortages")).hasSize(2);
                });

        assertThat(levelOf(variants.get(1)).reserved()).isZero();
    }

    @Test
    void parallelChangesToOneVariantAllCount() throws Exception {
        UUID shirt = variants.get(0);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                done.add(pool.submit(() -> {
                    start.await();
                    return inBusiness(() -> stock.receive(List.of(new StockService.Line(shirt, 1)), REF));
                }));
            }
            start.countDown();
            for (Future<?> future : done) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(levelOf(shirt).onHand()).isEqualTo(20);
    }

    @Test
    void theLedgerCannotBeRewritten() {
        UUID shirt = variants.get(0);
        inBusiness(() -> stock.receive(List.of(new StockService.Line(shirt, 5)), REF));

        assertThatThrownBy(() -> systemDatabase.jdbc().sql("update stock_movements set on_hand_delta = 500 where variant_id = ?")
                .param(shirt).update()).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> systemDatabase.jdbc().sql("delete from stock_movements where variant_id = ?")
                .param(shirt).update()).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void anotherBusinessesVariantIsNotFound() {
        ApiClient other = new ApiClient(port);
        TestAccounts.signUp(other, messages);
        UUID otherBusiness = TestBusinesses.create(other, "Other Stock Shop");

        assertThatThrownBy(() -> TenantScope.call(otherBusiness, () -> transactions.execute(status ->
                stock.receive(List.of(new StockService.Line(variants.get(0), 1)), REF))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code().code()).isEqualTo("resource.not_found"));
    }

    private <T> T inBusiness(Supplier<T> work) {
        return TenantScope.call(business, () -> transactions.execute(status -> work.get()));
    }

    private void assertCode(Supplier<?> work, String code) {
        assertThatThrownBy(() -> inBusiness(work)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code().code()).isEqualTo(code));
    }

    private StockLevel levelOf(UUID variant) {
        return systemDatabase.jdbc().sql("select variant_id, on_hand, reserved from stock_levels where variant_id = ?")
                .param(variant)
                .query((row, n) -> new StockLevel(row.getObject(1, UUID.class), row.getInt(2), row.getInt(3)))
                .single();
    }

    /** The newest movement's "after" columns equal the cached level, for every variant. */
    private boolean lastAfterMatchesLevel() {
        return systemDatabase.jdbc().sql("""
                        select count(*) from stock_levels l
                        where l.business_id = ? and (l.on_hand, l.reserved) <> (
                            select m.on_hand_after, m.reserved_after from stock_movements m
                            where m.variant_id = l.variant_id order by m.created_at desc, m.id desc limit 1)
                        """)
                .param(business).query(Long.class).single() == 0;
    }
}
