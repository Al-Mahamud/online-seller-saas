package com.mojud.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.billing.system.SubscriptionAdvancer;
import com.mojud.billing.system.SubscriptionJobs;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The subscription lifecycle (implementation-plan.md 10b): a test clock moved through trial, grace
 * and read-only, checking what is allowed at each step. The daily job is run for this business
 * only, so other tests' businesses are left alone.
 */
@IntegrationTest
class LifecycleTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    SubscriptionAdvancer advancer;

    @Autowired
    SubscriptionJobs jobs;

    @Autowired
    TestClock clock;

    ApiClient owner;
    UUID business;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Lifecycle Shop");
        owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectStatus(201);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aTrialRemindsThenTurnsReadOnly() {
        day(7);
        assertThat(billingNotices()).containsExactly("Your free trial ends in 7 days. Choose a plan to keep selling.");
        advancer.advance(business);
        assertThat(billingNotices()).as("a second run the same day sends nothing new").hasSize(1);
        day(4);
        day(2);
        assertThat(billingNotices()).startsWith("Your free trial ends in 1 day. Choose a plan to keep selling.",
                "Your free trial ends in 3 days. Choose a plan to keep selling.");
        canWrite(true);

        clock.advance(Duration.ofDays(1).plusMinutes(5));
        advancer.advance(business);

        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("READ_ONLY");
        assertThat(billingNotices().get(0)).startsWith("Your free trial has ended.");
        canWrite(false);
        // Reading, exporting, the profile, billing and notifications stay open.
        owner.get("/api/v1/products").expectStatus(200);
        owner.get("/api/v1/dashboard/today").expectStatus(200);
        assertThat(owner.download("/api/v1/exports/products.csv").statusCode()).isEqualTo(200);
        owner.patch("/api/v1/me", Map.of("name", "Still Me")).expectStatus(200);
        owner.post("/api/v1/notifications/read", Map.of("all", true)).expectStatus(204);
        owner.put("/api/v1/billing/plan", Map.of("planCode", "STARTER")).expectError(409, "billing.payment_needed");
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("subscription.changed");
    }

    @Test
    void readOnlyWarnsBeforeDeletion() {
        clock.advance(Duration.ofDays(14).plusMinutes(5));
        advancer.advance(business);

        day(60);
        assertThat(billingNotices().get(0)).isEqualTo(
                "Your shop has been read-only for a long time. Its data will be deleted in 30 days unless you renew.");
        day(25);
        assertThat(billingNotices().get(0)).contains("deleted in 5 days");

        day(6);
        assertThat(jobs.dataDeletion()).as("listed, not deleted: the job ships disabled").contains(business);
        owner.get("/api/v1/products").expectStatus(200);
    }

    @Test
    void anUnpaidPlanGetsAWeekOfGraceThenReadOnly() {
        systemDatabase.jdbc().sql("""
                        update subscriptions set plan_code = 'PRO', status = 'ACTIVE', current_period_end = ?
                        where business_id = ?
                        """)
                .params(Timestamp.from(clock.instant().plus(Duration.ofDays(10))), business).update();

        day(3);
        assertThat(billingNotices()).containsExactly("Your plan ends in 7 days. Pay to renew it.");

        clock.advance(Duration.ofDays(7).plusMinutes(5));
        advancer.advance(business);
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("GRACE");
        assertThat(billingNotices().get(0)).isEqualTo("Your plan has ended. Pay within 7 days to keep making changes.");
        canWrite(true);
        day(1);
        assertThat(billingNotices().get(0)).contains("within 6 days");

        clock.advance(Duration.ofDays(6));
        advancer.advance(business);
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("READ_ONLY");
        canWrite(false);
    }

    @Test
    void aSuspendedBusinessReachesOnlyBilling() {
        systemDatabase.jdbc().sql("update subscriptions set suspended_from = status, status = 'SUSPENDED' where business_id = ?")
                .param(business).update();

        owner.get("/api/v1/products").expectError(402, "subscription.suspended");
        owner.get("/api/v1/billing").expectStatus(200);
        // The web app can't open without knowing which business this is.
        owner.get("/api/v1/business").expectStatus(200);
        owner.get("/api/v1/billing/status").expectStatus(200);
        owner.get("/api/v1/business/settings").expectError(402, "subscription.suspended");
        // Paying doesn't lift a suspension: only an administrator does.
        owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectError(402, "subscription.suspended");
        owner.get("/api/v1/billing/quote?planCode=STARTER&months=1").expectError(402, "subscription.suspended");
        advancer.advance(business);
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("SUSPENDED");
    }

    @Test
    void aDowngradeWaitsForTheRenewal() {
        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'PRO', status = 'ACTIVE', current_period_end = ? where business_id = ?")
                .params(Timestamp.from(clock.instant().plus(Duration.ofDays(20))), business).update();

        ApiResponse scheduled = owner.put("/api/v1/billing/plan", Map.of("planCode", "STARTER")).expectStatus(200);
        assertThat(scheduled.<String>json("$.plan.code")).isEqualTo("PRO");
        assertThat(scheduled.<String>json("$.pendingPlanCode")).isEqualTo("STARTER");
        assertThat(owner.put("/api/v1/billing/plan", Map.of("planCode", "PRO")).expectStatus(200)
                .<Object>json("$.pendingPlanCode")).isNull();
        owner.put("/api/v1/billing/plan", Map.of("planCode", "TRIAL")).expectError(400, "validation.failed");

        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'STARTER' where business_id = ?").param(business).update();
        owner.put("/api/v1/billing/plan", Map.of("planCode", "PRO")).expectError(409, "billing.payment_needed");
    }

    @Test
    void theDailyJobGoesThroughEveryBusiness() {
        assertThat(jobs.lifecycle()).isGreaterThanOrEqualTo(1);
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("TRIALING");
    }

    /** Moves the clock {@code days} ahead and runs the day's lifecycle. */
    private void day(int days) {
        clock.advance(Duration.ofDays(days));
        advancer.advance(business);
    }

    private void canWrite(boolean allowed) {
        ApiResponse response = owner.post("/api/v1/products", Map.of("name", "Another " + UUID.randomUUID(), "price", 100));
        if (allowed) {
            response.expectStatus(201);
        } else {
            response.expectError(402, "subscription.read_only");
            owner.patch("/api/v1/business/settings", Map.of("lowStockDefault", 3)).expectError(402, "subscription.read_only");
        }
    }

    /** Billing notifications, newest first. */
    private List<String> billingNotices() {
        return owner.get("/api/v1/notifications?limit=50").json("$.items[?(@.type =~ /billing\\..*/)].title");
    }
}
