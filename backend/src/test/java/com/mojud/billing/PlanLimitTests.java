package com.mojud.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.billing.system.SubscriptionAdvancer;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The trial and plan limits (implementation-plan.md 10a). Starter's real limits are used: 500
 * variants, 2,000 orders a month, 3 staff seats, 90 days of reports, no exports.
 */
@IntegrationTest
class PlanLimitTests {

    static final List<String> TEN = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    SubscriptionAdvancer advancer;

    ApiClient owner;
    UUID business;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.createOnFree(owner, "Plan Shop");
    }

    @Test
    void aNewBusinessStartsOnFreeWhichNeverEnds() {
        ApiResponse billing = owner.get("/api/v1/billing").expectStatus(200);

        assertThat(billing.<String>json("$.plan.code")).isEqualTo("FREE");
        assertThat(billing.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(billing.<Object>json("$.trialEndsAt")).isNull();
        assertThat(billing.<Object>json("$.currentPeriodEnd")).isNull();
        assertThat(billing.<Map<String, Object>>json("$.usage.variants")).isEqualTo(Map.of("used", 0, "limit", 50));
        assertThat(billing.<Integer>json("$.usage.orders_per_month.limit")).isEqualTo(60);
        assertThat(billing.<Integer>json("$.usage.staff_seats.limit")).isZero();

        ApiResponse plans = owner.get("/api/v1/plans").expectStatus(200);
        assertThat(plans.<List<String>>json("$[*].code")).containsExactly("FREE", "STARTER", "PRO");
        assertThat(plans.<List<Integer>>json("$[*].priceMonthly")).containsExactly(0, 49900, 89900);
        assertThat(plans.<Integer>json("$[1].limits.staff_seats")).isEqualTo(10);
        assertThat(plans.<Integer>json("$[1].limits.report_days")).isEqualTo(365);
        assertThat(plans.<Object>json("$[2].limits.staff_seats")).isNull();

        // The daily job leaves it alone: there is no period to end.
        advancer.advance(business);
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("ACTIVE");
    }

    @Test
    void freeIsTheOwnerAloneSixtyOrdersAMonthAndThirtyDaysOfReports() {
        ApiResponse refused = owner.post("/api/v1/staff/invites", Map.of("to", "staff@example.com", "role", "STAFF"))
                .expectError(402, "plan.limit_reached");
        assertThat(refused.<String>json("$.metric")).isEqualTo("staff_seats");

        String variant = owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectStatus(201)
                .json("$.variants[0].id");
        String customer = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678"))
                .expectStatus(201).json("$.id");
        Map<String, Object> order = Map.of("customerId", customer, "items", List.of(Map.of("variantId", variant, "quantity", 1)));
        setOrdersUsed(59);
        owner.post("/api/v1/orders", order).expectStatus(201);
        owner.post("/api/v1/orders", order).expectError(402, "plan.limit_reached");

        String today = owner.get("/api/v1/dashboard/today").json("$.date");
        owner.get("/api/v1/reports/sales?from=" + LocalDate.parse(today).minusDays(29)).expectStatus(200);
        assertThat(owner.get("/api/v1/reports/sales?from=" + LocalDate.parse(today).minusDays(30))
                .expectError(402, "plan.limit_reached").<Integer>json("$.limit")).isEqualTo(30);
        owner.get("/api/v1/exports/orders.csv").expectError(402, "plan.limit_reached");
    }

    @Test
    void anOwnerWhoStartsAgainIsOnFreeToo() {
        // As if the first business had been deleted: the owner owns none.
        systemDatabase.jdbc().sql("update memberships set status = 'REMOVED' where business_id = ?").param(business).update();

        TestBusinesses.createOnFree(owner, "Second Shop");

        assertThat(owner.get("/api/v1/billing").<String>json("$.plan.code")).isEqualTo("FREE");
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("ACTIVE");
    }

    @Test
    void variantsStopExactlyAtTheLimitAndArchivingMakesRoom() {
        starter();
        for (int i = 0; i < 5; i++) {
            grid("Kurti " + i).expectStatus(201);
        }
        assertThat(owner.get("/api/v1/billing").<Integer>json("$.usage.variants.used")).isEqualTo(500);

        ApiResponse refused = owner.post("/api/v1/products", Map.of("name", "One more", "price", 100)).expectError(402, "plan.limit_reached");
        assertThat(refused.<String>json("$.metric")).isEqualTo("variants");
        assertThat(refused.<Integer>json("$.limit")).isEqualTo(500);

        // Everything already there is still readable.
        assertThat(owner.get("/api/v1/products?size=100").<List<Object>>json("$.items")).hasSize(5);

        String archived = owner.get("/api/v1/products?size=1").json("$.items[0].id");
        String variant = owner.get("/api/v1/products/" + archived).json("$.variants[0].id");
        owner.post("/api/v1/variants/" + variant + "/archive", null).expectStatus(200);
        owner.post("/api/v1/products", Map.of("name", "One more", "price", 100)).expectStatus(201);
        owner.post("/api/v1/variants/" + variant + "/restore", null).expectError(402, "plan.limit_reached");
    }

    @Test
    void anImportPastTheLimitFailsOnlyWhatDoesNotFit() {
        starter();
        for (int i = 0; i < 4; i++) {
            grid("Kurti " + i).expectStatus(201);
        }
        owner.post("/api/v1/products", Map.of("name", "Big", "price", 100, "options", List.of(
                Map.of("name", "A", "values", TEN), Map.of("name", "B", "values", TEN.subList(0, 9))))).expectStatus(201);
        StringBuilder csv = new StringBuilder("Name,Price,Size\n");
        for (int i = 1; i <= 12; i++) {
            csv.append("Scarf,50,S").append(i).append('\n');
        }
        csv.append("Wallet,95\n");
        String job = owner.upload("/api/v1/imports", "more.csv", csv.toString().getBytes(StandardCharsets.UTF_8))
                .expectStatus(201).json("$.id");

        owner.post("/api/v1/imports/" + job + "/commit", null).expectStatus(202);

        // 490 on sale: the Scarf's 12 would make 502 and fails whole; the Wallet fits.
        ApiResponse done = owner.get("/api/v1/imports/" + job);
        assertThat(done.<Map<String, Integer>>json("$.progress")).containsEntry("created", 1).containsEntry("failed", 12);
        assertThat(new String(owner.download("/api/v1/imports/" + job + "/errors.csv").body(),
                StandardCharsets.UTF_8)).contains("Your plan does not allow more of this");
    }

    @Test
    void parallelProductsAtTheLimitNeverPassIt() throws Exception {
        starter();
        for (int i = 0; i < 4; i++) {
            grid("Kurti " + i).expectStatus(201);
        }
        owner.post("/api/v1/products", Map.of("name", "Big", "price", 100, "options", List.of(
                Map.of("name", "A", "values", TEN), Map.of("name", "B", "values", TEN.subList(0, 9)))))
                .expectStatus(201);
        // 400 + 90 = 490: ten more fit; twenty try at once.
        List<Integer> statuses = parallel(IntStream.range(0, 20).mapToObj(i -> (Callable<Integer>) () ->
                owner.post("/api/v1/products", Map.of("name", "Single " + i, "price", 100)).status()).toList());

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(10);
        assertThat(statuses.stream().filter(s -> s == 402)).hasSize(10);
        assertThat(owner.get("/api/v1/billing").<Integer>json("$.usage.variants.used")).isEqualTo(500);
    }

    @Test
    void ordersStopAtTheMonthlyLimitEvenInParallel() throws Exception {
        starter();
        String variant = owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectStatus(201)
                .json("$.variants[0].id");
        String customer = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678"))
                .expectStatus(201).json("$.id");
        Map<String, Object> order = Map.of("customerId", customer, "items", List.of(Map.of("variantId", variant, "quantity", 1)));
        setOrdersUsed(1997);

        List<Integer> statuses = parallel(IntStream.range(0, 6).mapToObj(i -> (Callable<Integer>) () ->
                owner.post("/api/v1/orders", order).status()).toList());

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 402)).hasSize(3);
        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<Map<String, Object>>json("$.usage.orders_per_month")).isEqualTo(Map.of("used", 2000, "limit", 2000));
        ApiResponse refused = owner.post("/api/v1/orders", order).expectError(402, "plan.limit_reached");
        assertThat(refused.<String>json("$.metric")).isEqualTo("orders_per_month");
        // Orders already made are all there.
        assertThat(owner.get("/api/v1/orders").<List<Object>>json("$.items")).hasSize(3);
    }

    @Test
    void aResentOrderIsCountedOnce() {
        String variant = owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectStatus(201)
                .json("$.variants[0].id");
        String customer = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712345678"))
                .expectStatus(201).json("$.id");
        Map<String, Object> order = Map.of("customerId", customer, "items", List.of(Map.of("variantId", variant, "quantity", 1)));

        owner.post("/api/v1/orders", order, Map.of("Idempotency-Key", "once")).expectStatus(201);
        owner.post("/api/v1/orders", order, Map.of("Idempotency-Key", "once")).expectStatus(201);

        assertThat(owner.get("/api/v1/billing").<Integer>json("$.usage.orders_per_month.used")).isEqualTo(1);
    }

    @Test
    void staffSeatsCountOpenInvites() {
        starter();
        for (int i = 0; i < 10; i++) {
            owner.post("/api/v1/staff/invites", Map.of("to", "staff" + i + "@example.com", "role", "STAFF")).expectStatus(201);
        }
        assertThat(owner.get("/api/v1/billing").<Integer>json("$.usage.staff_seats.used")).isEqualTo(10);

        ApiResponse refused = owner.post("/api/v1/staff/invites", Map.of("to", "staff10@example.com", "role", "STAFF"))
                .expectError(402, "plan.limit_reached");
        assertThat(refused.<String>json("$.metric")).isEqualTo("staff_seats");
        // Inviting an address again replaces its invite: no new seat.
        owner.post("/api/v1/staff/invites", Map.of("to", "staff0@example.com", "role", "MANAGER")).expectStatus(201);
        String invite = owner.get("/api/v1/staff/invites").json("$[0].id");
        owner.delete("/api/v1/staff/invites/" + invite).expectStatus(204);
        owner.post("/api/v1/staff/invites", Map.of("to", "staff10@example.com", "role", "STAFF")).expectStatus(201);
    }

    @Test
    void starterReportsReachAYearAndDoNotExport() {
        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'PRO' where business_id = ?").param(business).update();
        String today = owner.get("/api/v1/dashboard/today").json("$.date");
        String longAgo = LocalDate.parse(today).minusDays(366 * 2).toString();
        owner.get("/api/v1/reports/sales?from=" + longAgo + "&to=" + LocalDate.parse(longAgo).plusDays(30)).expectStatus(200);
        assertThat(owner.download("/api/v1/exports/customers.csv").statusCode()).isEqualTo(200);

        starter();

        String edge = LocalDate.parse(today).minusDays(364).toString();
        String beyond = LocalDate.parse(today).minusDays(365).toString();
        owner.get("/api/v1/reports/sales?from=" + edge).expectStatus(200);
        ApiResponse refused = owner.get("/api/v1/reports/sales?from=" + beyond + "&to=" + LocalDate.parse(beyond).plusDays(5))
                .expectError(402, "plan.limit_reached");
        assertThat(refused.<Integer>json("$.limit")).isEqualTo(365);
        owner.get("/api/v1/dashboard/today?date=" + beyond).expectError(402, "plan.limit_reached");
        for (String export : List.of("orders", "products", "customers")) {
            ApiResponse noExport = owner.get("/api/v1/exports/" + export + ".csv").expectError(402, "plan.limit_reached");
            assertThat(noExport.<String>json("$.metric")).isEqualTo("exports");
        }
    }

    /** Moves the business to Starter, as a payment will (Phase 11). */
    private void starter() {
        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'STARTER', status = 'ACTIVE' where business_id = ?")
                .param(business).update();
    }

    private void setOrdersUsed(int used) {
        systemDatabase.jdbc().sql("""
                        insert into usage_counters (business_id, metric, period, used)
                        values (?, 'orders_per_month', to_char(now() at time zone 'Asia/Dhaka', 'YYYY-MM'), ?)
                        on conflict (business_id, metric, period) do update set used = excluded.used
                        """)
                .params(business, used).update();
    }

    /** A product of 10 x 10 = 100 variants. */
    private ApiResponse grid(String name) {
        return owner.post("/api/v1/products", Map.of("name", name, "price", 100, "options", List.of(
                Map.of("name", "Size", "values", TEN), Map.of("name", "Color", "values", TEN))));
    }

    private static List<Integer> parallel(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call.call();
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
}
