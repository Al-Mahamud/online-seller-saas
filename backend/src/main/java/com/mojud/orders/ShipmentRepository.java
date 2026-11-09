package com.mojud.orders;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    /**
     * What couriers owe, under a search: delivered, with the cash still with the courier. {@code search}
     * is "" for none; {@code pattern} is it as a LIKE pattern. Far bounds for no dates. PostgreSQL cannot
     * type a null parameter, so none are used.
     */
    String OWED = """
            from Shipment s join SalesOrder o on o.id = s.orderId
            where s.status = 'DELIVERED' and s.codStatus = 'WITH_COURIER'
              and s.deliveredAt >= :from and s.deliveredAt < :to
              and (:search = ''
                or lower(s.trackingNo) like :pattern escape '\\'
                or lower(o.numberLabel) like :pattern escape '\\'
                or function('search_text' as String, o.customerName) like :pattern escape '\\'
                or function('search_text' as String, o.deliveryName) like :pattern escape '\\'
                or o.customerPhone like :pattern escape '\\'
                or o.deliveryPhone like :pattern escape '\\')
            """;

    @Query("select s from Shipment s where s.orderId = :orderId order by s.shippedAt")
    List<Shipment> findByOrder(UUID orderId);

    @Query("select s from Shipment s where s.orderId = :orderId and s.status = 'IN_TRANSIT'")
    Optional<Shipment> findInTransit(UUID orderId);

    /** The shipment the customer received (the last delivered one). */
    @Query("select s from Shipment s where s.orderId = :orderId and s.status = 'DELIVERED' order by s.shippedAt desc limit 1")
    Optional<Shipment> findDelivered(UUID orderId);

    /** "" for any status, COD status or courier (lower-cased). Newest first. */
    @Query("""
            select s from Shipment s
            where (:status = '' or s.status = :status)
              and (:codStatus = '' or s.codStatus = :codStatus)
              and (:courier = '' or lower(s.courier) = :courier)
            order by s.shippedAt desc, s.id desc""")
    List<Shipment> findFiltered(String status, String codStatus, String courier, Limit limit);

    /** One courier's parcels under a search, last delivered first. */
    @Query(value = "select s " + OWED + " and lower(s.courier) = :courier order by s.deliveredAt desc, s.id desc",
            countQuery = "select count(s) " + OWED + " and lower(s.courier) = :courier")
    Page<Shipment> findOwedPage(String courier, Instant from, Instant to, String search, String pattern, Pageable pageable);

    /** Every parcel of one courier under a search, for a payout that covers them across pages. */
    @Query("select s.id " + OWED + " and lower(s.courier) = :courier")
    List<UUID> findOwedIds(String courier, Instant from, Instant to, String search, String pattern);

    /** Rows of lower-cased courier, parcels and cash, for each courier with a parcel under a search. */
    @Query("select lower(s.courier), count(s), coalesce(sum(s.codCollected), 0) " + OWED + " group by lower(s.courier)")
    List<Object[]> sumOwed(Instant from, Instant to, String search, String pattern);

    /**
     * All of each courier's COD money, with no search: rows of lower-cased courier, a name as typed, status,
     * parcels and cash, what was collected when delivered and what's to be collected on the way.
     */
    @Query("""
            select lower(s.courier), min(s.courier), s.status, count(s), coalesce(sum(coalesce(s.codCollected, s.codExpected)), 0)
            from Shipment s
            where s.codStatus = 'WITH_COURIER' and s.status in ('DELIVERED', 'IN_TRANSIT')
            group by lower(s.courier), s.status""")
    List<Object[]> sumWithCouriers();

    /** Locked for this transaction, in id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id in :ids order by s.id")
    List<Shipment> findLocked(Collection<UUID> ids);

    /** Courier names this business has used, most used first (as typed most often). */
    @Query("""
            select s.courier from Shipment s
            group by s.courier
            order by count(s) desc, max(s.shippedAt) desc""")
    List<String> findCouriers();
}
