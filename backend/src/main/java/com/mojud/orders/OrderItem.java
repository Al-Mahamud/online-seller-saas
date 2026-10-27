package com.mojud.orders;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** One line of an order; product name, option label, SKU, price and cost as they were when ordered. */
@Entity
@Table(name = "order_items")
class OrderItem {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID orderId;

    private UUID variantId;

    private String productName;

    private String variantLabel;

    private String sku;

    private int quantity;

    private long unitPrice;

    private Long unitCost;

    private int position;

    protected OrderItem() {
    }

    OrderItem(UUID orderId, Totals.Line line, int position) {
        this.id = Ids.newId();
        this.orderId = orderId;
        this.variantId = line.variantId();
        this.productName = line.productName();
        this.variantLabel = line.variantLabel();
        this.sku = line.sku();
        this.quantity = line.quantity();
        this.unitPrice = line.unitPrice();
        this.unitCost = line.unitCost();
        this.position = position;
    }

    UUID getId() {
        return id;
    }

    UUID getOrderId() {
        return orderId;
    }

    UUID getVariantId() {
        return variantId;
    }

    String getProductName() {
        return productName;
    }

    String getVariantLabel() {
        return variantLabel;
    }

    String getSku() {
        return sku;
    }

    int getQuantity() {
        return quantity;
    }

    long getUnitPrice() {
        return unitPrice;
    }

    Long getUnitCost() {
        return unitCost;
    }
}
