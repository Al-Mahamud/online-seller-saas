package com.mojud.billing.system;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.mojud.billing.gateway.SslCommerzClient;
import com.mojud.shared.db.system.SystemDatabase;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Every 15 minutes, asks SSLCommerz about payments still pending after 30 minutes, in case its IPN
 * was lost (backend-plan.md §9): a payment it has is validated and applied like an IPN's; one it
 * failed is marked so; one unanswered for two days is given up (FAILED).
 */
@Component
public class PendingPayments {

    private static final Logger log = LoggerFactory.getLogger(PendingPayments.class);
    static final Duration WAIT = Duration.ofMinutes(30);
    static final Duration GIVE_UP = Duration.ofDays(2);

    private record Pending(UUID businessId, String tranId, Timestamp createdAt) {
    }

    private final SystemDatabase database;
    private final SslCommerzClient gateway;
    private final PaymentConfirmations confirmations;
    private final Clock clock;

    PendingPayments(SystemDatabase database, SslCommerzClient gateway, PaymentConfirmations confirmations, Clock clock) {
        this.database = database;
        this.gateway = gateway;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    /** @return how many attempts were looked at */
    public int run() {
        List<Pending> pending = database.jdbc().sql("""
                        select business_id, tran_id, created_at from payment_attempts
                        where status = 'PENDING' and gateway = 'SSLCOMMERZ' and created_at < ?
                        order by created_at
                        """)
                .param(Timestamp.from(clock.instant().minus(WAIT)))
                .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3)))
                .list();
        for (Pending attempt : pending) {
            try {
                check(attempt);
            } catch (RuntimeException e) {
                log.error("[JOB-PENDING-PAYMENTS] checking payment {} failed", attempt.tranId(), e);
            }
        }
        return pending.size();
    }

    private void check(Pending attempt) {
        List<SslCommerzClient.Payment> found = gateway.query(attempt.tranId());
        SslCommerzClient.Payment paid = found.stream().filter(SslCommerzClient.Payment::paid).findFirst().orElse(null);
        if (paid != null) {
            // The query's answer is the gateway's own; still validated by val_id like an IPN.
            confirmations.confirm(attempt.businessId(), attempt.tranId(), gateway.validate(paid.valId()));
        } else if (found.stream().anyMatch(p -> p.status().equals("FAILED") || p.status().equals("CANCELLED"))) {
            confirmations.failed(attempt.businessId(), attempt.tranId(), found.get(0).status());
        } else if (attempt.createdAt().toInstant().isBefore(clock.instant().minus(GIVE_UP))) {
            confirmations.failed(attempt.businessId(), attempt.tranId(), "FAILED");
        }
    }

    @Bean
    RecurringTask<Void> pendingPaymentsTask() {
        return Tasks.recurring("pending-payments", Schedules.fixedDelay(Duration.ofMinutes(15)))
                .execute((instance, context) -> run());
    }
}
