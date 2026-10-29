package com.mojud.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.inventory.system.StockReconciliation;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The load test (implementation-plan.md 13c): 50 staff members create and confirm orders on the
 * same few variants for 10 minutes, while some cancel and the owner keeps restocking. Afterwards:
 * no request failed with a server error, stock never went below zero or held more than it had,
 * every variant's reserved count equals its confirmed orders, on-hand equals what was received,
 * and the ledger matches the cached levels. Latencies are written to a report.
 *
 * <p>Skipped in normal builds. Run it with
 * {@code ./mvnw test -Dtest=OrderLoadTests -Dload.minutes=10 [-Dload.staff=50]}; the report is
 * written to {@code target/load-test-report.md}. Client and server share the machine, so the
 * numbers are a floor for a server of the same size.
 */
@IntegrationTest
@EnabledIfSystemProperty(named = "load.minutes", matches = "\\d+(\\.\\d+)?")
class OrderLoadTests {

    static final int VARIANTS = 6;
    static final int START_STOCK = 60;
    static final int CUSTOMERS = 20;
    static final int MIRPUR = 9027;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    StockReconciliation reconciliation;

    /** Milliseconds per operation, and how each ended. */
    final Map<String, Queue<Long>> latencies = new ConcurrentHashMap<>();
    final Map<String, AtomicLong> outcomes = new ConcurrentHashMap<>();
    final Queue<String> serverErrors = new ConcurrentLinkedQueue<>();
    final AtomicInteger received = new AtomicInteger();
    final AtomicInteger badSamples = new AtomicInteger();
    final AtomicInteger samples = new AtomicInteger();

    @Test
    void fiftyStaffOnSharedStockNeverOversell() throws Exception {
        double minutes = Double.parseDouble(System.getProperty("load.minutes"));
        int staffCount = Integer.getInteger("load.staff", 50);

        // A business on Pro with six variants of two products, a little stock, and customers.
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        UUID business = TestBusinesses.create(owner, "Load Test Shop");
        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'PRO', status = 'ACTIVE', current_period_end = ? where business_id = ?")
                .params(Timestamp.from(Instant.now().plus(Duration.ofDays(30))), business).update();
        List<String> variants = new ArrayList<>();
        for (String name : List.of("Cotton Kurti", "Jamdani Saree")) {
            ApiResponse product = owner.post("/api/v1/products", Map.of("name", name, "price", 150000, "costPrice", 90000,
                    "options", List.of(Map.of("name", "Size", "values", List.of("S", "M", "L"))))).expectStatus(201);
            variants.addAll(product.json("$.variants[*].id"));
        }
        assertThat(variants).hasSize(VARIANTS);
        for (String variant : variants) {
            receive(owner, variant, START_STOCK);
        }
        List<String> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMERS; i++) {
            customers.add(owner.post("/api/v1/customers", Map.of("name", "Customer " + i,
                    "phone", String.format("0171%07d", ThreadLocalRandom.current().nextInt(10_000_000)),
                    "address", "House " + i, "areaId", MIRPUR)).expectStatus(201).json("$.id"));
        }

        // The staff: real accounts, signed in; added straight to the business (Pro allows 10 seats).
        List<ApiClient> staff = new ArrayList<>();
        for (int i = 0; i < staffCount; i++) {
            ApiClient member = new ApiClient(port);
            TestAccounts.signUp(member, messages);
            UUID userId = UUID.fromString(member.get("/api/v1/me").json("$.id"));
            systemDatabase.jdbc().sql("""
                            insert into memberships (id, business_id, user_id, role, status, last_used_at, created_at, updated_at,
                                                     version, joined_at)
                            values (?, ?, ?, 'STAFF', 'ACTIVE', now(), now(), now(), 0, now())
                            """)
                    .params(UUID.randomUUID(), business, userId).update();
            member.get("/api/v1/business").expectStatus(200);
            staff.add(member);
        }

        Instant started = Instant.now();
        long deadline = System.nanoTime() + (long) (minutes * 60_000_000_000L);
        ExecutorService pool = Executors.newFixedThreadPool(staffCount + 2);
        for (ApiClient member : staff) {
            pool.submit(() -> work(member, variants, customers, deadline));
        }
        pool.submit(() -> restock(owner, variants, deadline));
        pool.submit(() -> watch(business, deadline));
        pool.shutdown();
        assertThat(pool.awaitTermination((long) (minutes * 60) + 120, TimeUnit.SECONDS)).as("the run finished").isTrue();
        Duration ran = Duration.between(started, Instant.now());

        // The checks.
        Map<String, int[]> levels = new TreeMap<>();
        systemDatabase.jdbc().sql("select variant_id, on_hand, reserved from stock_levels where business_id = ?").param(business)
                .query((rs, n) -> levels.put(rs.getString(1), new int[] {rs.getInt(2), rs.getInt(3)})).list();
        Map<String, Integer> confirmedQuantity = new HashMap<>();
        systemDatabase.jdbc().sql("""
                        select i.variant_id, sum(i.quantity) from order_items i join orders o on o.id = i.order_id
                        where o.business_id = ? and o.status = 'CONFIRMED' group by i.variant_id
                        """)
                .param(business).query((rs, n) -> confirmedQuantity.put(rs.getString(1), rs.getInt(2))).list();
        long orders = systemDatabase.jdbc().sql("select count(*) from orders where business_id = ?").param(business)
                .query(Long.class).single();
        long numbers = systemDatabase.jdbc().sql("select count(distinct number) from orders where business_id = ?").param(business)
                .query(Long.class).single();
        List<StockReconciliation.Mismatch> mismatches = reconciliation.run().stream()
                .filter(mismatch -> mismatch.businessId().equals(business)).toList();
        int onHandTotal = levels.values().stream().mapToInt(level -> level[0]).sum();

        Path report = Path.of("target/load-test-report.md");
        Files.writeString(report, report(staffCount, ran, levels, confirmedQuantity, orders, numbers, mismatches, onHandTotal));
        System.out.println(Files.readString(report));

        assertThat(serverErrors).as("server errors").isEmpty();
        assertThat(badSamples.get()).as("stock samples below zero or over-reserved").isZero();
        for (Map.Entry<String, int[]> level : levels.entrySet()) {
            assertThat(level.getValue()[1]).as("reserved = confirmed orders, %s", level.getKey())
                    .isEqualTo(confirmedQuantity.getOrDefault(level.getKey(), 0));
            assertThat(level.getValue()[1]).isLessThanOrEqualTo(level.getValue()[0]);
        }
        assertThat(onHandTotal).as("on hand = everything received (nothing shipped)").isEqualTo(received.get());
        assertThat(numbers).as("order numbers unique").isEqualTo(orders);
        assertThat(mismatches).as("ledger matches the cached levels").isEmpty();
        assertThat(count("confirm.ok")).as("some orders were confirmed").isPositive();
    }

    /** One staff member: draft an order of one or two variants, confirm it; now and then cancel an earlier one. */
    private void work(ApiClient member, List<String> variants, List<String> customers, long deadline) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<String> confirmed = new ArrayList<>();
        try {
            while (System.nanoTime() < deadline) {
                List<Map<String, Object>> items = new ArrayList<>();
                List<String> picked = new ArrayList<>(variants);
                Collections.shuffle(picked, random);
                for (String variant : picked.subList(0, 1 + random.nextInt(2))) {
                    items.add(Map.of("variantId", variant, "quantity", 1 + random.nextInt(3)));
                }
                ApiResponse draft = timed("create", () -> member.post("/api/v1/orders",
                        Map.of("customerId", customers.get(random.nextInt(customers.size())), "items", items)));
                if (draft.status() != 201) {
                    unexpected("create", draft);
                    continue;
                }
                String id = draft.json("$.id");
                ApiResponse confirm = timed("confirm", () -> member.post("/api/v1/orders/" + id + "/confirm", null));
                if (confirm.status() == 200) {
                    outcome("confirm.ok");
                    confirmed.add(id);
                } else if (confirm.status() == 409 && "stock.insufficient".equals(code(confirm))) {
                    outcome("confirm.out_of_stock");
                    timed("cancel", () -> member.post("/api/v1/orders/" + id + "/cancel", Map.of("reason", "No stock")));
                } else {
                    unexpected("confirm", confirm);
                }
                if (!confirmed.isEmpty() && random.nextInt(4) == 0) {
                    String old = confirmed.remove(random.nextInt(confirmed.size()));
                    ApiResponse cancel = timed("cancel", () -> member.post("/api/v1/orders/" + old + "/cancel",
                            Map.of("reason", "Customer changed their mind")));
                    if (cancel.status() == 200) {
                        outcome("cancel.ok");
                    } else {
                        unexpected("cancel", cancel);
                    }
                }
                Thread.sleep(random.nextInt(100));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            serverErrors.add("client: " + e);
        }
    }

    /** The owner receives ten more of some variant every second. */
    private void restock(ApiClient owner, List<String> variants, long deadline) {
        try {
            while (System.nanoTime() < deadline) {
                String variant = variants.get(ThreadLocalRandom.current().nextInt(variants.size()));
                ApiResponse response = timed("restock", () -> owner.post("/api/v1/stock/adjustments",
                        Map.of("lines", List.of(Map.of("variantId", variant, "kind", "RECEIVE", "quantity", 10)))));
                if (response.status() == 201) {
                    received.addAndGet(10);
                } else {
                    unexpected("restock", response);
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Every half second, straight from the database: nothing negative, nothing over-reserved. */
    private void watch(UUID business, long deadline) {
        try {
            while (System.nanoTime() < deadline) {
                long bad = systemDatabase.jdbc().sql("""
                                select count(*) from stock_levels
                                where business_id = ? and (on_hand < 0 or reserved < 0 or reserved > on_hand)
                                """)
                        .param(business).query(Long.class).single();
                samples.incrementAndGet();
                badSamples.addAndGet((int) bad);
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void receive(ApiClient owner, String variant, int quantity) {
        owner.post("/api/v1/stock/adjustments", Map.of("lines", List.of(Map.of("variantId", variant, "kind", "RECEIVE",
                "quantity", quantity)))).expectStatus(201);
        received.addAndGet(quantity);
    }

    private ApiResponse timed(String operation, java.util.function.Supplier<ApiResponse> call) {
        long start = System.nanoTime();
        ApiResponse response = call.get();
        latencies.computeIfAbsent(operation, key -> new ConcurrentLinkedQueue<>()).add((System.nanoTime() - start) / 1_000_000);
        if (response.status() >= 500) {
            serverErrors.add(operation + " " + response.status() + " " + response.body());
        }
        return response;
    }

    private void unexpected(String operation, ApiResponse response) {
        outcome(operation + ".unexpected_" + response.status());
        if (response.status() < 500) {
            serverErrors.add(operation + " answered " + response.status() + ": " + response.body());
        }
    }

    private void outcome(String name) {
        outcomes.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
    }

    private long count(String name) {
        return outcomes.getOrDefault(name, new AtomicLong()).get();
    }

    private static String code(ApiResponse response) {
        try {
            return response.json("$.code");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String report(int staffCount, Duration ran, Map<String, int[]> levels, Map<String, Integer> confirmedQuantity,
            long orders, long numbers, List<StockReconciliation.Mismatch> mismatches, int onHandTotal) {
        StringBuilder out = new StringBuilder();
        var os = ManagementFactory.getOperatingSystemMXBean();
        long memory = os instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getTotalMemorySize() : 0;
        String postgres = systemDatabase.jdbc().sql("show server_version").query(String.class).single();
        out.append("## Run\n\n")
                .append(String.format(Locale.ROOT, "- %d staff and one owner restocking, for %.1f minutes, on %d shared variants "
                        + "(%d units each at the start, +10 to one of them every second)%n", staffCount, ran.toSeconds() / 60.0,
                        VARIANTS, START_STOCK))
                .append(String.format(Locale.ROOT, "- Machine: %d CPU threads, %.1f GB memory, %s; Java %s; PostgreSQL %s "
                        + "(embedded); client, app and database on the same machine%n", os.getAvailableProcessors(),
                        memory / 1e9, System.getProperty("os.name"), System.getProperty("java.version"), postgres))
                .append(String.format(Locale.ROOT, "- Started %s%n%n", Instant.now().minus(ran)));
        out.append("## Outcomes\n\n| | Count |\n|---|---:|\n");
        new TreeMap<>(outcomes).forEach((name, value) -> out.append("| ").append(name).append(" | ").append(value.get()).append(" |\n"));
        out.append(String.format(Locale.ROOT, "| orders created | %d |%n| requests per second (all kinds) | %.1f |%n%n", orders,
                latencies.values().stream().mapToLong(Queue::size).sum() / (double) Math.max(1, ran.toSeconds())));
        out.append("## Latency (ms, measured by the client)\n\n| Operation | Requests | p50 | p95 | p99 | max |\n|---|---:|---:|---:|---:|---:|\n");
        new TreeMap<>(latencies).forEach((name, queue) -> {
            List<Long> sorted = new ArrayList<>(queue);
            Collections.sort(sorted);
            out.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d |%n", name, sorted.size(), percentile(sorted, 50),
                    percentile(sorted, 95), percentile(sorted, 99), sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1)));
        });
        out.append("\n## Checks\n\n");
        out.append(String.format(Locale.ROOT, "- Server errors (5xx or unexpected answers): %d%s%n", serverErrors.size(),
                serverErrors.isEmpty() ? "" : " (first: " + serverErrors.peek() + ")"));
        out.append(String.format(Locale.ROOT, "- Stock sampled from the database %d times during the run; negative or over-reserved: %d%n",
                samples.get(), badSamples.get()));
        boolean reservedMatches = levels.entrySet().stream()
                .allMatch(level -> level.getValue()[1] == confirmedQuantity.getOrDefault(level.getKey(), 0));
        out.append(String.format(Locale.ROOT, "- Reserved equals the confirmed orders' quantities for every variant: %s%n",
                reservedMatches ? "yes" : "NO"));
        out.append(String.format(Locale.ROOT, "- On hand (%d) equals everything received (%d): %s%n", onHandTotal, received.get(),
                onHandTotal == received.get() ? "yes" : "NO"));
        out.append(String.format(Locale.ROOT, "- Ledger (sum of stock movements) matches the cached levels: %s%n",
                mismatches.isEmpty() ? "yes" : "NO (" + mismatches + ")"));
        out.append(String.format(Locale.ROOT, "- Order numbers unique: %s (%d orders, %d numbers)%n", numbers == orders ? "yes" : "NO",
                orders, numbers));
        out.append("\n| Variant | On hand | Reserved | Confirmed orders' quantity |\n|---|---:|---:|---:|\n");
        levels.forEach((variant, level) -> out.append(String.format(Locale.ROOT, "| %s | %d | %d | %d |%n", variant.substring(variant.length() - 8),
                level[0], level[1], confirmedQuantity.getOrDefault(variant, 0))));
        return out.toString();
    }

    private static long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(percent / 100.0 * sorted.size()) - 1));
    }
}
