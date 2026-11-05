package com.mojud.admin;

import com.mojud.audit.ActivityMetrics;
import com.mojud.billing.BillingAdministration;
import com.mojud.identity.UserMetrics;
import com.mojud.tenancy.BusinessDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform's numbers (implementation-plan.md 12b; product plan goals): sign-ups, trials, paying
 * businesses, conversion, MRR and weekly active businesses. Composed from what each module counts.
 */
@RestController
class AdminMetricsController {

    /**
     * {@code conversion}: paying now ÷ businesses whose trial has ended (null before any has);
     * {@code mrrPoisha}: paying businesses' monthly prices; {@code weeklyActive}: businesses where a
     * member changed something in the last 7 days; {@code grace}: paid plans past their end, in their
     * week of grace; {@code plans}: businesses on each plan; {@code waitingTransfers},
     * {@code waitingPoisha}, {@code oldestWaiting}: bank transfers not yet marked paid.
     */
    record Metrics(long users, long signUps7d, long signUps30d, long businesses, long businesses7d, long trialing, long paying,
            long readOnly, long suspended, long everPaid, @Nullable BigDecimal conversion, long mrrPoisha, long weeklyActive,
            long grace, List<BillingAdministration.PlanCount> plans, long waitingTransfers, long waitingPoisha,
            @Nullable Instant oldestWaiting, Instant at) {
    }

    private final UserMetrics users;
    private final BusinessDirectory businesses;
    private final BillingAdministration billing;
    private final ActivityMetrics activity;
    private final Clock clock;

    AdminMetricsController(UserMetrics users, BusinessDirectory businesses, BillingAdministration billing,
            ActivityMetrics activity, Clock clock) {
        this.users = users;
        this.businesses = businesses;
        this.billing = billing;
        this.activity = activity;
        this.clock = clock;
    }

    @GetMapping("/api/v1/admin/metrics")
    Metrics metrics() {
        Instant now = clock.instant();
        Instant week = now.minus(Duration.ofDays(7));
        BillingAdministration.Metrics money = billing.metrics();
        BigDecimal conversion = money.trialsEnded() == 0 ? null
                : BigDecimal.valueOf(money.paying()).divide(BigDecimal.valueOf(money.trialsEnded()), 4, RoundingMode.HALF_UP);
        return new Metrics(users.users(), users.signUpsSince(week), users.signUpsSince(now.minus(Duration.ofDays(30))),
                businesses.businesses(), businesses.createdSince(week), money.trialing(), money.paying(), money.readOnly(),
                money.suspended(), money.everPaid(), conversion, money.mrr(), activity.activeBusinessesSince(week),
                money.grace(), money.plans(), money.waitingTransfers(), money.waitingAmount(), money.oldestWaiting(), now);
    }
}
