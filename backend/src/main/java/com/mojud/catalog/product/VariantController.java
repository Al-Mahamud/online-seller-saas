package com.mojud.catalog.product;

import com.mojud.catalog.CatalogLimits;
import com.mojud.shared.paging.CursorPage;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** One variant's SKU, prices and low-stock threshold; managers and owners only. */
@RestController
@RequestMapping("/api/v1/variants")
@RequiresRole(BusinessRole.MANAGER)
class VariantController {

    private final ProductService products;

    VariantController(ProductService products) {
        this.products = products;
    }

    /**
     * Fields left out stay as they are. An empty {@code sku} generates one; {@code clearCostPrice}
     * and {@code useDefaultThreshold} clear those; an empty {@code name} or {@code size} removes
     * it. {@code version} refuses a stale change.
     */
    record VariantRequest(
            @Size(max = 200) String name,
            @Size(max = 200) String size,
            @Size(max = 200) String sku,
            @PositiveOrZero @Max(ProductController.MAX_POISHA) Long price,
            @PositiveOrZero @Max(ProductController.MAX_POISHA) Long costPrice,
            Boolean clearCostPrice,
            @PositiveOrZero @Max(CatalogLimits.MAX_LOW_STOCK_THRESHOLD) Integer lowStockThreshold,
            Boolean useDefaultThreshold,
            Long version) {
    }

    @PatchMapping("/{id}")
    ProductViews.Variant update(@PathVariable UUID id, @Valid @RequestBody VariantRequest body) {
        return products.updateVariant(id, new ProductService.VariantCommand(body.name(), body.size(), body.sku(), body.price(), body.costPrice(),
                Boolean.TRUE.equals(body.clearCostPrice()), body.lowStockThreshold(),
                Boolean.TRUE.equals(body.useDefaultThreshold()), body.version()));
    }

    /** The variant's stock history, newest first; every member may read it. */
    @GetMapping("/{id}/movements")
    @RequiresRole(BusinessRole.STAFF)
    CursorPage<ProductViews.Movement> history(@PathVariable UUID id, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return products.history(id, cursor, limit);
    }

    /** For a combination the seller does not make; kept, not deleted. */
    @PostMapping("/{id}/archive")
    ProductViews.Variant archive(@PathVariable UUID id) {
        return products.changeVariantStatus(id, ProductStatus.ARCHIVED);
    }

    @PostMapping("/{id}/restore")
    ProductViews.Variant restore(@PathVariable UUID id) {
        return products.changeVariantStatus(id, ProductStatus.ACTIVE);
    }
}
