package com.mojud.orders;

import com.mojud.customers.CustomerDeleting;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.tenant.TenantScope;
import javax.sql.DataSource;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders keep copies of their customer's details. When a customer is deleted: refused while any of
 * their orders is still in progress; otherwise their finished orders keep the sale but lose the
 * name, phone and address (the district stays, for reports), and their link (the database clears
 * it when the customer row goes).
 */
@Component
class CustomerCopies {

    static final String DELETED_NAME = "Deleted customer";
    /** A well-formed number that is no one's, kept where a phone is required. */
    static final String DELETED_PHONE = "+8801300000000";

    private final JdbcClient jdbc;

    CustomerCopies(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(CustomerDeleting event) {
        String business = TenantScope.current().orElseThrow().toString();
        long open = jdbc.sql("""
                        select count(*) from orders where business_id = ?::uuid and customer_id = ?
                          and status in ('DRAFT', 'CONFIRMED', 'PACKED', 'SHIPPED')
                        """)
                .params(business, event.customerId()).query(Long.class).single();
        if (open > 0) {
            throw new ApiException(ErrorCode.CUSTOMER_HAS_OPEN_ORDERS);
        }
        jdbc.sql("""
                        update orders set customer_name = ?, customer_phone = ?, delivery_name = ?, delivery_phone = ?,
                               delivery_address = '', delivery_area_id = null
                        where business_id = ?::uuid and customer_id = ?
                        """)
                .params(DELETED_NAME, DELETED_PHONE, DELETED_NAME, DELETED_PHONE, business, event.customerId())
                .update();
    }
}
