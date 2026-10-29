package com.mojud.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.id.Ids;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.CrossTenant;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAdmins;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import com.mojud.tenancy.system.BusinessDeletions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The owner's export, deleting a business, deleting a customer, and platform metrics (implementation-plan.md 12b). */
@IntegrationTest
class DataRightsTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    BusinessDeletions deletions;

    @Autowired
    TestClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void theExportHoldsTheOwnersBusinessOnlyWhateverThePlan() {
        CrossTenant.Tenant mine = tenant();
        CrossTenant.Tenant theirs = tenant();
        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'STARTER', status = 'ACTIVE' where business_id = ?")
                .param(mine.businessId()).update();
        mine.owner().get("/api/v1/exports/customers.csv").expectError(402, "plan.limit_reached");

        var download = mine.owner().download("/api/v1/business/export");

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/zip");
        Map<String, String> files = unzip(download.body());
        assertThat(files).containsOnlyKeys("orders.csv", "products.csv", "customers.csv");
        assertThat(files.get("products.csv")).contains(mine.productName(), mine.sku());
        assertThat(files.get("customers.csv")).contains(mine.customerName());
        assertThat(files.get("orders.csv")).contains(mine.orderPrefix() + "1001", mine.orderPrefix() + "1003");
        String all = String.join("\n", files.values());
        for (String marker : theirs.markers()) {
            assertThat(all).as("leaks %s", marker).doesNotContain(marker);
        }
        mine.staff().get("/api/v1/business/export").expectStatus(403);
    }

    @Test
    void aDeletedBusinessIsGoneFromEveryTableAfterTheUndoWindow() {
        CrossTenant.Tenant tenant = tenant();
        UUID business = tenant.businessId();
        systemDatabase.jdbc().sql("""
                        insert into billing_ledger (id, business_id, invoice_id, type, amount, vat_amount, method, reference, created_at)
                        values (?, ?, ?, 'ADJUSTMENT', 0, 0, 'BANK_TRANSFER', 'deletion test', now())
                        """)
                .params(Ids.newId(), business, tenant.invoiceId()).update();
        Map<String, Long> before = rowsOf(business);
        assertThat(before.values().stream().filter(n -> n > 0).count()).as("tables with this business's rows").isGreaterThan(30);

        tenant.owner().post("/api/v1/business/deletion", Map.of("password", "wrong")).expectError(400, "account.wrong_password");
        ApiResponse asked = tenant.owner().post("/api/v1/business/deletion",
                Map.of("password", tenant.ownerAccount().password())).expectStatus(202);
        Instant due = Instant.parse(asked.json("$.deletionDueAt"));
        assertThat(Duration.between(Instant.now(), due)).isBetween(Duration.ofDays(7).minusMinutes(5), Duration.ofDays(7));
        assertThat(tenant.owner().get("/api/v1/business").<String>json("$.deletionDueAt")).isNotNull();
        tenant.owner().post("/api/v1/business/deletion", Map.of("password", tenant.ownerAccount().password()))
                .expectError(409, "business.deletion_pending");
        tenant.staff().post("/api/v1/business/deletion", Map.of("password", "x")).expectStatus(403);

        // Changed their mind, then asked again.
        tenant.owner().delete("/api/v1/business/deletion").expectStatus(204);
        assertThat(tenant.owner().get("/api/v1/business").<Object>json("$.deletionDueAt")).isNull();
        tenant.owner().post("/api/v1/business/deletion", Map.of("password", tenant.ownerAccount().password())).expectStatus(202);

        clock.advance(Duration.ofDays(6));
        assertThat(deletions.run()).doesNotContain(business);
        // Nothing gone yet (the audit log has grown: asking and cancelling are recorded).
        Map<String, Long> waiting = rowsOf(business);
        waiting.remove("audit_logs");
        before.remove("audit_logs");
        assertThat(waiting).isEqualTo(before);

        clock.advance(Duration.ofDays(1).plusMinutes(1));
        assertThat(deletions.run()).contains(business);

        assertThat(rowsOf(business)).allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
        assertThat(systemDatabase.jdbc().sql("select count(*) from businesses where id = ?").param(business)
                .query(Long.class).single()).isZero();
        // The owner's account stays, and so does their used trial (detached).
        assertThat(tenant.owner().get("/api/v1/businesses").<List<Object>>json("$")).isEmpty();
        assertThat(systemDatabase.jdbc().sql("select count(*) from trial_claims where claim_key = ?")
                .param("user:" + tenant.ownerId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aReadOnlyBusinessCanStillTakeOrDeleteItsData() {
        ApiClient owner = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(owner, messages);
        UUID business = TestBusinesses.create(owner, "Leaving Shop");
        systemDatabase.jdbc().sql("update subscriptions set status = 'READ_ONLY', read_only_since = ? where business_id = ?")
                .params(Timestamp.from(Instant.now()), business).update();

        assertThat(owner.download("/api/v1/business/export").statusCode()).isEqualTo(200);
        owner.post("/api/v1/business/deletion", Map.of("password", account.password())).expectStatus(202);
        owner.delete("/api/v1/business/deletion").expectStatus(204);
    }

    @Test
    void aCustomerIsDeletedOnceNothingIsInProgress() {
        CrossTenant.Tenant tenant = tenant();
        ApiClient owner = tenant.owner();
        // The fixture's customer has a draft (in progress), a shipped and a returned order.
        owner.delete("/api/v1/customers/" + tenant.customerId()).expectError(409, "customer.has_open_orders");
        owner.post("/api/v1/orders/" + tenant.orderId() + "/cancel", Map.of("reason", "Customer asked to be forgotten"))
                .expectStatus(200);
        owner.post("/api/v1/orders/" + tenant.shippedOrderId() + "/deliver", null).expectStatus(200);
        tenant.staff().delete("/api/v1/customers/" + tenant.customerId()).expectStatus(403);

        owner.delete("/api/v1/customers/" + tenant.customerId()).expectStatus(204);

        owner.get("/api/v1/customers/" + tenant.customerId()).expectError(404, "resource.not_found");
        ApiResponse kept = owner.get("/api/v1/orders/" + tenant.shippedOrderId());
        assertThat(kept.<String>json("$.status")).isEqualTo("DELIVERED");
        assertThat(kept.<Object>json("$.customer.id")).isNull();
        assertThat(kept.<String>json("$.customer.name")).isEqualTo("Deleted customer");
        assertThat(kept.<String>json("$.delivery.phone")).isEqualTo("+8801300000000");
        assertThat(kept.<String>json("$.delivery.address")).isEmpty();
        assertThat(kept.<Integer>json("$.total")).isPositive();
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("customer.deleted");
        assertThat(String.join("\n", unzip(owner.download("/api/v1/business/export").body()).values()))
                .doesNotContain(tenant.customerName(), tenant.customerPhone().substring(4));
    }

    @Test
    void metricsCountThePlatform() {
        ApiClient admin = admin();
        ApiResponse before = admin.get("/api/v1/admin/metrics").expectStatus(200);

        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        UUID paying = TestBusinesses.create(owner, "Paying Shop");
        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'PRO', status = 'ACTIVE' where business_id = ?")
                .param(paying).update();
        owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectStatus(201);
        ApiClient other = new ApiClient(port);
        TestAccounts.signUp(other, messages);
        TestBusinesses.create(other, "Trial Shop");

        ApiResponse after = admin.get("/api/v1/admin/metrics").expectStatus(200);

        assertThat(delta(before, after, "users")).isEqualTo(2);
        assertThat(delta(before, after, "signUps7d")).isEqualTo(2);
        assertThat(delta(before, after, "businesses")).isEqualTo(2);
        assertThat(delta(before, after, "trialing")).isEqualTo(1);
        assertThat(delta(before, after, "paying")).isEqualTo(1);
        assertThat(delta(before, after, "mrrPoisha")).isEqualTo(299000);
        assertThat(delta(before, after, "weeklyActive")).isEqualTo(2);
    }

    private static long delta(ApiResponse before, ApiResponse after, String field) {
        return ((Number) after.json("$." + field)).longValue() - ((Number) before.json("$." + field)).longValue();
    }

    /** Rows of this business in every table with a business_id (whatever tables exist). */
    private Map<String, Long> rowsOf(UUID business) {
        List<String> tables = systemDatabase.jdbc().sql("""
                        select c.relname from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        join pg_attribute a on a.attrelid = c.oid and a.attname = 'business_id' and not a.attisdropped
                        where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'trial_claims'
                        order by c.relname
                        """)
                .query(String.class).list();
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : tables) {
            rows.put(table, systemDatabase.jdbc().sql("select count(*) from " + table + " where business_id = ?")
                    .param(business).query(Long.class).single());
        }
        return rows;
    }

    private CrossTenant.Tenant tenant() {
        return CrossTenant.setUp(port, messages, (businessId, userId) -> systemDatabase.jdbc()
                .sql("select id from memberships where business_id = ? and user_id = ?")
                .params(businessId, userId).query(UUID.class).single());
    }

    private static Map<String, String> unzip(byte[] zip) {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private ApiClient admin() {
        return TestAdmins.signIn(port, messages, systemDatabase, clock);
    }
}
