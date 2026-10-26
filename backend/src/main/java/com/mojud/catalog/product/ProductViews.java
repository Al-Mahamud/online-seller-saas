package com.mojud.catalog.product;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mojud.shared.files.FileLinks;
import com.mojud.shared.money.Money;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the API returns for products. Cost price and margin are filled only for managers and
 * owners and are left out of the JSON otherwise: staff never receive them (backend-plan.md §7).
 */
final class ProductViews {

    private ProductViews() {
    }

    record Tag(UUID id, String name) {
    }

    record Option(UUID id, String name, List<String> values) {
    }

    record Variant(
            UUID id,
            String sku,
            String label,
            Map<String, String> attributes,
            Money price,
            @JsonInclude(JsonInclude.Include.NON_NULL) Money costPrice,
            @JsonInclude(JsonInclude.Include.NON_NULL) Money margin,
            /** Null: the business's default applies. */
            Integer lowStockThreshold,
            ProductStatus status,
            long version) {

        /** {@code optionOrder}: the product's option names, which order the attributes and label. */
        static Variant of(ProductVariant variant, boolean showCost, List<String> optionOrder) {
            Long cost = showCost ? variant.getCostPrice() : null;
            Map<String, String> attributes = new LinkedHashMap<>();
            optionOrder.forEach(option -> {
                if (variant.getAttributes().containsKey(option)) {
                    attributes.put(option, variant.getAttributes().get(option));
                }
            });
            variant.getAttributes().forEach(attributes::putIfAbsent);
            return new Variant(variant.getId(), variant.getSku(), VariantGrid.label(attributes),
                    attributes, Money.ofPoisha(variant.getPrice()),
                    cost == null ? null : Money.ofPoisha(cost),
                    cost == null ? null : Money.ofPoisha(variant.getPrice() - cost),
                    variant.getLowStockThreshold(), variant.getStatus(), variant.getVersion());
        }
    }

    /** {@code variantId} null: shown for the whole product. */
    record Image(UUID id, UUID fileId, UUID variantId, String large, String small) {

        static Image of(ProductImage image) {
            FileLinks.Links links = FileLinks.of(image.getFileId());
            return new Image(image.getId(), image.getFileId(), image.getVariantId(), links.large(), links.small());
        }
    }

    record Detail(UUID id, String name, String description, UUID categoryId, ProductStatus status, List<Tag> tags,
            List<Image> images, List<Option> options, List<Variant> variants, Instant createdAt, long version) {
    }

    /** One row of the product list: prices of the active variants only; the first image's small copy. */
    record Summary(UUID id, String name, UUID categoryId, ProductStatus status, List<Tag> tags, int activeVariants,
            Money minPrice, Money maxPrice, String thumbnail, Instant createdAt) {
    }
}
