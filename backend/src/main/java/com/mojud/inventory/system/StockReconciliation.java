package com.mojud.inventory.system;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.time.DhakaTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Nightly check that every cached stock level still equals the sum of its ledger (backend-plan.md
 * §5.2). Nothing should ever differ; a difference means a bug or a hand edit, so each one is logged
 * as an error (reported to Sentry from Phase 13b) and nothing is "fixed" silently. Runs over every
 * business, so it uses the system role.
 */
@Component
public class StockReconciliation {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliation.class);

    public record Mismatch(UUID businessId, UUID variantId, int onHand, int reserved, int ledgerOnHand,
            int ledgerReserved) {
    }

    private final SystemDatabase database;

    StockReconciliation(SystemDatabase database) {
        this.database = database;
    }

    public List<Mismatch> run() {
        List<Mismatch> mismatches = database.jdbc().sql("""
                        select l.business_id, l.variant_id, l.on_hand, l.reserved,
                               coalesce(sum(m.on_hand_delta), 0), coalesce(sum(m.reserved_delta), 0)
                        from stock_levels l
                        left join stock_movements m on m.business_id = l.business_id and m.variant_id = l.variant_id
                        group by l.business_id, l.variant_id, l.on_hand, l.reserved
                        having l.on_hand <> coalesce(sum(m.on_hand_delta), 0)
                            or l.reserved <> coalesce(sum(m.reserved_delta), 0)
                        """)
                .query((row, n) -> new Mismatch(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getInt(3), row.getInt(4), row.getInt(5), row.getInt(6)))
                .list();
        mismatches.forEach(m -> log.error("Stock cache differs from the ledger: {}", m));
        log.info("Stock reconciliation: {} mismatch(es)", mismatches.size());
        return mismatches;
    }

    @Bean
    RecurringTask<Void> stockReconciliationTask() {
        return Tasks.recurring("stock-reconciliation", Schedules.cron("0 30 2 * * *", DhakaTime.ZONE))
                .execute((instance, context) -> run());
    }
}
