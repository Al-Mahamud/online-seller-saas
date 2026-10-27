package com.mojud.orders;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    @Query("select i from OrderItem i where i.orderId = :orderId order by i.position")
    List<OrderItem> findByOrder(UUID orderId);

    @Query("select i.orderId, count(i) from OrderItem i where i.orderId in :orderIds group by i.orderId")
    List<Object[]> countByOrders(Collection<UUID> orderIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from OrderItem i where i.orderId = :orderId")
    void deleteByOrder(UUID orderId);
}
