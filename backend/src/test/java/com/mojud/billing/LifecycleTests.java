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
        business = TestBusinesses.createOnFree(owner, "Lifecycle Shop");
        owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectStatus(201);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void anOldTrialRemindsThenEndsOnFree() {
        // No business gets a trial now; one an administrator gave, or one from before, still ends.
        systemDatabase.jdbc().sql("update subscriptions set plan_code = 'TRIAL', status = 'TRIALING', trial_ends_at = ? where business_id = ?")
                .params(Timestamp.from(clock.instant().plus(Duration.ofDays(14))), business).update();
        day(7);
        assertThat(billingNotices()).containsExactly("Your free trial ends in 7 days. After it, the shop is on the Free plan.");
        advancer.advance(business);
        assertThat(billingNotices()).as("a second run the same day sends nothing new").hasSize(1);

        clock.advance(Duration.ofDays(7).plusMinutes(5));
        advancer.advance(business);

        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.plan.code")).isEqualTo("FREE");
        assertThat(billing.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(billing.<Object>json("$.trialEndsAt")).isNull();
        assertThat(billingNotices().get(0)).isEqualTo("Your free trial has ended. The shop is on the Free plan now.");
        canWrite(true);
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("subscription.changed");
    }

    @Test
    void readOnlyWarnsBeforeDeletion() {
        systemDatabase.jdbc().sql("""
                        update subscriptions set plan_code = 'STARTER', status = 'READ_ONLY', read_only_since = ?
                        where business_id = ?
                        """)
                .params(Timestamp.from(clock.instant()), business).update();
        canWrite(false);

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
    void aLapsedPlanCanGoToFreeAtOnce() {
        systemDatabase.jdbc().sql("""
                        update subscriptions set plan_code = 'STARTER', status = 'READ_ONLY', read_only_since = ?
                        where business_id = ?
                        """)
                .params(Timestamp.from(clock.instant()), business).update();
        canWrite(false);
        owner.put("/api/v1/billing/plan", Map.of("planCode", "STARTER")).expectError(409, "billing.payment_needed");

        ApiResponse free = owner.put("/api/v1/billing/plan", Map.of("planCode", "FREE")).expectStatus(200);

        assertThat(free.<String>json("$.plan.code")).isEqualTo("FREE");
        assertThat(free.<String>json("$.status")).isEqualTo("ACTIVE");
        canWrite(true);
    }

    @Test
    void aMoveToFreeWaitsForTheRenewalAndNeedsNoGrace() {
        systemDatabase.jdbc().sql("""
                        update subscriptions set plan_code = 'PRO', status = 'ACTIVE', current_period_start = ?, current_period_end = ?
                        where business_id = ?
                        """)
                .params(Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plus(Duration.ofDays(10))), business)
                .update();
        ApiResponse scheduled = owner.put("/api/v1/billing/plan", Map.of("planCode", "FREE")).expectStatus(200);
        assertThat(scheduled.<String>json("$.plan.code")).isEqualTo("PRO");
        assertThat(scheduled.<String>json("$.pendingPlanCode")).isEqualTo("FREE");

        clock.advance(Duration.ofDays(10).plusMinutes(5));
        advancer.advance(business);

        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.plan.code")).isEqualTo("FREE");
        assertThat(billing.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(billing.<Object>json("$.currentPeriodEnd")).isNull();
        assertThat(billing.<Object>json("$.pendingPlanCode")).isNull();
        canWrite(true);
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
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("ACTIVE");
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
        return owner.get("/api/v1/notifications?size=50").json("$.items[?(@.type =~ /billing\\..*/)].title");
    }
}
