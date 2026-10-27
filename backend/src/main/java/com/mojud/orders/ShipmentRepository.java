package com.mojud.orders;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    @Query("select s from Shipment s where s.orderId = :orderId order by s.shippedAt")
    List<Shipment> findByOrder(UUID orderId);

    @Query("select s from Shipment s where s.orderId = :orderId and s.status = 'IN_TRANSIT'")
    Optional<Shipment> findInTransit(UUID orderId);

    /** "" for any status, COD status or courier (lower-cased). Newest first. */
    @Query("""
            select s from Shipment s
            where (:status = '' or s.status = :status)
              and (:codStatus = '' or s.codStatus = :codStatus)
              and (:courier = '' or lower(s.courier) = :courier)
            order by s.shippedAt desc, s.id desc""")
    List<Shipment> findFiltered(String status, String codStatus, String courier, Limit limit);

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
