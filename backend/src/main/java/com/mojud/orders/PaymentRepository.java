package com.mojud.orders;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Query("select p from Payment p where p.orderId = :orderId order by p.receivedAt, p.id")
    List<Payment> findByOrder(UUID orderId);
}
