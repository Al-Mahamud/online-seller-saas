package com.mojud.orders;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * The order with its row locked for this transaction: a status change or an edit reads the
     * status only after any other change to the order has committed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<Order> findLocked(UUID id);

    /**
     * Newest first. No null parameters (PostgreSQL cannot type them):
     * every status when none is chosen, "" for no phone, 0 for no number, far bounds for no dates.
     */
    @Query("""
            select o from SalesOrder o
            where o.status in :statuses
              and o.createdAt >= :from and o.createdAt < :to
              and (:phone = '' or o.customerPhone = :phone or o.deliveryPhone = :phone)
              and (:number = 0 or o.number = :number)
            order by o.createdAt desc, o.id desc""")
    Page<Order> findPage(Collection<OrderStatus> statuses, Instant from, Instant to, String phone, long number,
            Pageable pageable);

    /** Rows of status and count, for the same filters as {@link #findPage}. */
    @Query("""
            select o.status, count(o) from SalesOrder o
            where o.createdAt >= :from and o.createdAt < :to
              and (:phone = '' or o.customerPhone = :phone or o.deliveryPhone = :phone)
              and (:number = 0 or o.number = :number)
            group by o.status""")
    List<Object[]> countByStatus(Instant from, Instant to, String phone, long number);
}
