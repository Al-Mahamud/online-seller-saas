package com.mojud.billing.system;

import com.mojud.shared.db.system.SystemDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Billing reads and writes that span businesses or plans, with the system role, for administration. */
@Component
public class BillingSystemQueries {

    public record Row(String planCode, String status, Instant trialEndsAt, Instant currentPeriodEnd) {
    }

    /** Bank transfers still waiting to be seen arriving: how many, their total (poisha), the oldest. */
    public record Waiting(long count, long amount, Instant oldest) {
    }

    private final SystemDatabase database;

    BillingSystemQueries(SystemDatabase database) {
        this.database = database;
    }

    public Map<UUID, Row> subscriptions(Collection<UUID> businessIds) {
        Map<UUID, Row> rows = new HashMap<>();
        if (businessIds.isEmpty()) {
            return rows;
        }
        database.jdbc().sql("""
                        select business_id, plan_code, status, trial_ends_at, current_period_end from subscriptions
                        where business_id = any (?)
                        """)
                .param(businessIds.toArray(UUID[]::new))
                .query((rs, n) -> rows.put(rs.getObject(1, UUID.class), new Row(rs.getString(2), rs.getString(3),
                        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)))))
                .list();
        return rows;
    }

    /** [trialing, paying, readOnly, suspended, trialsEnded, everPaid, mrr, grace] */
    public long[] metrics(Instant now) {
        return database.jdbc().sql("""
                        select count(*) filter (where s.status = 'TRIALING'),
                               count(*) filter (where s.status in ('ACTIVE', 'GRACE') and p.price_monthly > 0),
                               count(*) filter (where s.status = 'READ_ONLY'),
                               count(*) filter (where s.status = 'SUSPENDED'),
                               count(*) filter (where s.trial_ends_at <= ? or s.plan_code <> 'TRIAL'),
                               (select count(distinct business_id) from invoices where status = 'PAID'),
                               coalesce(sum(p.price_monthly) filter (where s.status in ('ACTIVE', 'GRACE') and p.price_monthly > 0), 0),
                               count(*) filter (where s.status = 'GRACE')
                        from subscriptions s join plans p on p.code = s.plan_code
                        """)
                .param(Timestamp.from(now))
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8)})
                .single();
    }

    /** Businesses on each plan, cheapest first: every plan offered, and any other a business is on. */
    public Map<String, Long> planCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        database.jdbc().sql("""
                        select p.code, count(s.business_id) from plans p left join subscriptions s on s.plan_code = p.code
                        group by p.code, p.price_monthly, p.is_public
                        having p.is_public or count(s.business_id) > 0
                        order by p.price_monthly, p.code
                        """)
                .query((rs, n) -> counts.put(rs.getString(1), rs.getLong(2)))
                .list();
        return counts;
    }

    public Waiting waitingTransfers() {
        return database.jdbc().sql("""
                        select count(*), coalesce(sum(amount), 0), min(created_at) from invoices
                        where status = 'OPEN' and pay_by = 'BANK_TRANSFER'
                        """)
                .query((rs, n) -> new Waiting(rs.getLong(1), rs.getLong(2), instant(rs.getTimestamp(3))))
                .single();
    }

    public void updatePlan(String code, String nameEn, String nameBn, long priceMonthly, boolean isPublic, String limitsJson) {
        database.jdbc().sql("""
                        update plans set name_en = ?, name_bn = ?, price_monthly = ?, is_public = ?, limits = ?::jsonb
                        where code = ?
                        """)
                .params(nameEn, nameBn, priceMonthly, isPublic, limitsJson, code)
                .update();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
