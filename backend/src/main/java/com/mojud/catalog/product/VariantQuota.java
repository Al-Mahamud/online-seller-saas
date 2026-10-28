package com.mojud.catalog.product;

import com.mojud.billing.Entitlements;
import com.mojud.billing.Metric;
import com.mojud.billing.UsageSource;
import com.mojud.shared.tenant.TenantScope;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plan's variant limit (implementation-plan.md 10a): variants on sale, i.e. active variants of
 * active products. Any change is allowed unless it leaves more on sale than before and more than
 * the plan allows, so archiving makes room, and a business over its limit (after a downgrade)
 * keeps everything it has. Callers hold the catalog's per-business lock, so changes count in turn.
 */
@Component
class VariantQuota implements UsageSource {

    private final Entitlements entitlements;
    private final JdbcClient jdbc;

    VariantQuota(Entitlements entitlements, DataSource dataSource) {
        this.entitlements = entitlements;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Runs a change, then refuses it (rolling back) if it put the business over its limit. */
    <T> T guarded(Supplier<T> change, Runnable flush) {
        long before = used();
        T result = change.get();
        flush.run();
        long after = used();
        if (after > before) {
            entitlements.requireWithin(Metric.VARIANTS, after);
        }
        return result;
    }

    @Override
    public Metric metric() {
        return Metric.VARIANTS;
    }

    @Override
    @Transactional(readOnly = true)
    public long used() {
        return jdbc.sql("""
                        select count(*) from product_variants v join products p on p.id = v.product_id
                        where v.business_id = ? and v.status = 'ACTIVE' and p.status = 'ACTIVE'
                        """)
                .param(TenantScope.current().orElseThrow())
                .query(Long.class)
                .single();
    }
}
