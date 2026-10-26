package com.mojud.catalog.product;

import com.mojud.inventory.StockChanged;
import com.mojud.inventory.StockLevel;
import com.mojud.inventory.StockQueries;
import com.mojud.notifications.Notifier;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.BusinessDefaults;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Low stock (implementation-plan.md 4b). A variant is low when its available stock is at or below
 * its alert level: its own {@code lowStockThreshold}, or the business's default. The level lives
 * here, with the product, so the alert is raised here too: inventory announces every change
 * ({@link StockChanged}) and knows nothing of products.
 */
@Component
class LowStock {

    /** One row of the "running low" list. */
    record Item(UUID productId, String productName, UUID variantId, String label, String sku, int available,
            int threshold) {
    }

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductOptionRepository options;
    private final StockQueries stock;
    private final BusinessDefaults defaults;
    private final Notifier notifier;
    private final Clock clock;

    LowStock(ProductRepository products, ProductVariantRepository variants, ProductOptionRepository options,
            StockQueries stock, BusinessDefaults defaults, Notifier notifier, Clock clock) {
        this.products = products;
        this.variants = variants;
        this.options = options;
        this.stock = stock;
        this.defaults = defaults;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * After a change that lowered a sellable variant's available stock to its alert level or
     * below, notifies managers and owners: at most once per variant per (Dhaka) day.
     */
    @EventListener
    void on(StockChanged event) {
        List<StockChanged.Change> lowered = event.changes().stream().filter(StockChanged.Change::lowered).toList();
        if (lowered.isEmpty()) {
            return;
        }
        Map<UUID, ProductVariant> byId = variants.findAllById(lowered.stream().map(StockChanged.Change::variantId).toList())
                .stream().collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        Map<UUID, Product> productsById = products.findAllById(byId.values().stream().map(ProductVariant::getProductId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Integer businessDefault = null;
        for (StockChanged.Change change : lowered) {
            ProductVariant variant = byId.get(change.variantId());
            Product product = variant == null ? null : productsById.get(variant.getProductId());
            if (product == null || !variant.isActive() || product.getStatus() != ProductStatus.ACTIVE) {
                continue;
            }
            if (variant.getLowStockThreshold() == null && businessDefault == null) {
                businessDefault = defaults.lowStockDefault();
            }
            int threshold = variant.getLowStockThreshold() != null ? variant.getLowStockThreshold() : businessDefault;
            if (change.availableAfter() <= threshold) {
                String label = label(variant);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("productId", product.getId().toString());
                payload.put("variantId", variant.getId().toString());
                payload.put("sku", variant.getSku());
                payload.put("available", change.availableAfter());
                payload.put("threshold", threshold);
                payload.put("args", List.of(product.getName(), label.isEmpty() ? "" : " (" + label + ")",
                        change.availableAfter(), threshold));
                notifier.notify("stock.low", BusinessRole.MANAGER, payload,
                        "stock.low:" + variant.getId() + ":" + DhakaTime.today(clock));
            }
        }
    }

    /** Sellable variants at or below their alert level, emptiest first. */
    @Transactional(readOnly = true)
    List<Item> list() {
        List<ProductVariant> sellable = variants.findSellable();
        if (sellable.isEmpty()) {
            return List.of();
        }
        Map<UUID, StockLevel> levels = stock.levels(sellable.stream().map(ProductVariant::getId).toList());
        Set<UUID> productIds = sellable.stream().map(ProductVariant::getProductId).collect(Collectors.toSet());
        Map<UUID, Product> productsById = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        int businessDefault = defaults.lowStockDefault();
        return sellable.stream()
                .map(variant -> {
                    int threshold = variant.getLowStockThreshold() != null ? variant.getLowStockThreshold() : businessDefault;
                    int available = levels.get(variant.getId()).available();
                    return available > threshold ? null : new Item(variant.getProductId(),
                            productsById.get(variant.getProductId()).getName(), variant.getId(), label(variant),
                            variant.getSku(), available, threshold);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Item::available).thenComparing(Item::productName))
                .toList();
    }

    /** "M / Black" in the product's option order (stored attributes keep no order). */
    private String label(ProductVariant variant) {
        List<String> order = options.findByProductIdOrderByPosition(variant.getProductId()).stream()
                .map(ProductOption::getName).toList();
        return order.stream().filter(variant.getAttributes()::containsKey).map(variant.getAttributes()::get)
                .collect(Collectors.joining(" / "));
    }
}
