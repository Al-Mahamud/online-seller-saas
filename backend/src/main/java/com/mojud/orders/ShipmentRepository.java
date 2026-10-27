package com.mojud.orders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    @Query("select s from Shipment s where s.orderId = :orderId order by s.shippedAt")
    List<Shipment> findByOrder(UUID orderId);

    @Query("select s from Shipment s where s.orderId = :orderId and s.status = 'IN_TRANSIT'")
    Optional<Shipment> findInTransit(UUID orderId);

    /** Courier names this business has used, most used first (as typed most often). */
    @Query("""
            select s.courier from Shipment s
            group by s.courier
            order by count(s) desc, max(s.shippedAt) desc""")
    List<String> findCouriers();
}
