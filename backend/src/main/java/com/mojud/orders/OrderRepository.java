package com.mojud.orders;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Newest first, after the cursor position. No null parameters (PostgreSQL cannot type them):
     * every status when none is chosen, "" for no phone, 0 for no number, far bounds for no dates.
     */
    @Query("""
            select o from SalesOrder o
            where o.status in :statuses
              and o.createdAt >= :from and o.createdAt < :to
              and (:phone = '' or o.customerPhone = :phone or o.deliveryPhone = :phone)
              and (:number = 0 or o.number = :number)
              and (o.createdAt < :afterAt or (o.createdAt = :afterAt and o.id < :afterId))
            order by o.createdAt desc, o.id desc""")
    List<Order> findPage(Collection<OrderStatus> statuses, Instant from, Instant to, String phone, long number,
            Instant afterAt, UUID afterId, Limit limit);
}
