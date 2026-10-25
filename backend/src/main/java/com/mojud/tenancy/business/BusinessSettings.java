package com.mojud.tenancy.business;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_settings")
class BusinessSettings {

    @Id
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    private StockCommitPoint stockCommitPoint;

    private int lowStockDefault;

    private String orderNumberPrefix;

    private String invoiceFooter;

    private Instant updatedAt;

    @Version
    private Long version;

    protected BusinessSettings() {
    }

    /** The defaults every new business starts with. */
    static BusinessSettings defaults(UUID businessId, Instant now) {
        BusinessSettings settings = new BusinessSettings();
        settings.businessId = businessId;
        settings.stockCommitPoint = StockCommitPoint.SHIP;
        settings.lowStockDefault = 5;
        settings.orderNumberPrefix = "";
        settings.invoiceFooter = "";
        settings.updatedAt = now;
        return settings;
    }

    /** Null arguments keep the current value. */
    void change(StockCommitPoint commitPoint, Integer lowStock, String prefix, String footer, Instant now) {
        if (commitPoint != null) {
            stockCommitPoint = commitPoint;
        }
        if (lowStock != null) {
            lowStockDefault = lowStock;
        }
        if (prefix != null) {
            orderNumberPrefix = prefix;
        }
        if (footer != null) {
            invoiceFooter = footer.strip();
        }
        updatedAt = now;
    }

    StockCommitPoint getStockCommitPoint() {
        return stockCommitPoint;
    }

    int getLowStockDefault() {
        return lowStockDefault;
    }

    String getOrderNumberPrefix() {
        return orderNumberPrefix;
    }

    String getInvoiceFooter() {
        return invoiceFooter;
    }
}
