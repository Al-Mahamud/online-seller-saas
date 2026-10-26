package com.mojud.catalog.product;

import com.mojud.shared.id.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/** One sellable combination of a product's option values. Prices are whole poisha. */
@Entity
@Table(name = "product_variants")
class ProductVariant {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    private UUID productId;

    private String sku;

    /** jsonb keeps no key order: views put the values in the product's option order. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> attributes;

    private long price;

    private Long costPrice;

    private Integer lowStockThreshold;

    @Enumerated(EnumType.STRING)
    private ProductStatus status;

    private int position;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected ProductVariant() {
    }

    ProductVariant(UUID productId, String sku, Map<String, String> attributes, long price, Long costPrice,
            int position, Instant now) {
        this.id = Ids.newId();
        this.productId = productId;
        this.sku = sku;
        this.attributes = new LinkedHashMap<>(attributes);
        this.price = price;
        this.costPrice = costPrice;
        this.status = ProductStatus.ACTIVE;
        this.position = position;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The grid changed around this variant: its option values (after renames), status and place. */
    void regrid(Map<String, String> newAttributes, ProductStatus newStatus, int newPosition, Instant now) {
        if (!newAttributes.equals(attributes) || newStatus != status || newPosition != position) {
            attributes = new LinkedHashMap<>(newAttributes);
            status = newStatus;
            position = newPosition;
            updatedAt = now;
        }
    }

    void changeStatus(ProductStatus newStatus, Instant now) {
        status = newStatus;
        updatedAt = now;
    }

    void changeSku(String newSku, Instant now) {
        sku = newSku;
        updatedAt = now;
    }

    void changePrices(long newPrice, Long newCostPrice, Instant now) {
        price = newPrice;
        costPrice = newCostPrice;
        updatedAt = now;
    }

    void changeLowStockThreshold(Integer threshold, Instant now) {
        lowStockThreshold = threshold;
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getProductId() {
        return productId;
    }

    String getSku() {
        return sku;
    }

    Map<String, String> getAttributes() {
        return attributes;
    }

    long getPrice() {
        return price;
    }

    Long getCostPrice() {
        return costPrice;
    }

    Integer getLowStockThreshold() {
        return lowStockThreshold;
    }

    ProductStatus getStatus() {
        return status;
    }

    boolean isActive() {
        return status == ProductStatus.ACTIVE;
    }

    int getPosition() {
        return position;
    }

    Long getVersion() {
        return version;
    }
}
