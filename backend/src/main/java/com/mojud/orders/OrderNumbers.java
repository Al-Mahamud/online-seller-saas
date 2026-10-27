package com.mojud.orders;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessDefaults;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order numbers per business, from 1001 (backend-plan.md §6, rule 5). The counter row is taken in
 * the order's own transaction: parallel orders wait for each other, and an order that rolls back
 * returns its number, so numbers have no gaps in practice.
 */
@Component
class OrderNumbers {

    static final long FIRST = 1001;

    record Number(long value, String label) {
    }

    private final JdbcClient jdbc;
    private final BusinessDefaults settings;

    OrderNumbers(DataSource dataSource, BusinessDefaults settings) {
        this.jdbc = JdbcClient.create(dataSource);
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Number next() {
        long number = jdbc.sql("""
                        insert into order_counters (business_id, next_number) values (?, ?)
                        on conflict (business_id) do update set next_number = order_counters.next_number + 1
                        returning next_number - 1
                        """)
                .params(TenantScope.current().orElseThrow(), FIRST + 1)
                .query(Long.class)
                .single();
        String prefix = settings.orderNumberPrefix();
        return new Number(number, (prefix.isEmpty() ? "#" : prefix) + number);
    }
}
