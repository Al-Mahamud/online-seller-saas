package com.mojud.billing.system;

import com.mojud.shared.db.system.SystemDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Billing reads and writes that span businesses or plans, with the system role, for administration. */
@Component
public class BillingSystemQueries {

    public record Row(String planCode, String status, Instant trialEndsAt, Instant currentPeriodEnd) {
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
