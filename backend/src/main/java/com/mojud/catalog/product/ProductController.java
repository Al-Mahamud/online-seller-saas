package com.mojud.catalog.product;

import com.mojud.shared.paging.CursorPage;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Products of the selected business: everyone reads them, managers and owners change them
 * (backend-plan.md §7). Prices are whole poisha. Staff responses carry no cost price or margin.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiresRole(BusinessRole.MANAGER)
class ProductController {

    /** Up to ৳10,000,000,000 in poisha: far beyond any product, well inside a long. */
    static final long MAX_POISHA = 1_000_000_000_000L;

    private final ProductService products;
    private final ProductImageService images;

    ProductController(ProductService products, ProductImageService images) {
        this.products = products;
        this.images = images;
    }

    /**
     * Newest first, page by page. With {@code q}: the best 50 matches by name (also with small
     * typos) or SKU, on one page. A top-level {@code categoryId} also finds products in its
     * subcategories.
     */
    @GetMapping
    @RequiresRole(BusinessRole.STAFF)
    CursorPage<ProductViews.Summary> list(@RequestParam(defaultValue = "ACTIVE") ProductStatus status,
            @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return products.list(status, categoryId, tagId, q, cursor, limit);
    }

    @GetMapping("/{id}")
    @RequiresRole(BusinessRole.STAFF)
    ProductViews.Detail get(@PathVariable UUID id) {
        return products.get(id);
    }

    record OptionRequest(UUID id, @NotBlank String name, @NotNull List<String> values) {
    }

    /**
     * Without options: one variant, with {@code sku} if given. With options: one variant per
     * combination, SKUs generated. All variants start with {@code price} and {@code costPrice}.
     */
    record CreateRequest(
            @NotBlank @Size(max = 400) String name,
            @Size(max = 10000) String description,
            UUID categoryId,
            List<UUID> tagIds,
            @Valid List<OptionRequest> options,
            @NotNull @PositiveOrZero @Max(MAX_POISHA) Long price,
            @PositiveOrZero @Max(MAX_POISHA) Long costPrice,
            String sku) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProductViews.Detail create(@Valid @RequestBody CreateRequest body) {
        return products.create(new ProductService.CreateCommand(body.name(), body.description(), body.categoryId(),
                body.tagIds(), inputs(body.options()), body.price(), body.costPrice(), body.sku()));
    }

    /**
     * Fields left out stay as they are; {@code tagIds} replaces all tags; {@code clearCategory}
     * removes the category. {@code version} (from the last read) refuses the change if someone
     * else changed the product since.
     */
    record UpdateRequest(
            @Size(max = 400) String name,
            @Size(max = 10000) String description,
            UUID categoryId,
            Boolean clearCategory,
            List<UUID> tagIds,
            Long version) {
    }

    @PatchMapping("/{id}")
    ProductViews.Detail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body) {
        return products.update(id, new ProductService.UpdateCommand(body.name(), body.description(), body.categoryId(),
                Boolean.TRUE.equals(body.clearCategory()), body.tagIds(), body.version()));
    }

    @PostMapping("/{id}/archive")
    ProductViews.Detail archive(@PathVariable UUID id) {
        return products.changeStatus(id, ProductStatus.ARCHIVED);
    }

    @PostMapping("/{id}/restore")
    ProductViews.Detail restore(@PathVariable UUID id) {
        return products.changeStatus(id, ProductStatus.ACTIVE);
    }

    /** New variants get {@code price}/{@code costPrice}, or the first active variant's when left out. */
    record OptionsRequest(
            @NotNull @Valid List<OptionRequest> options,
            @PositiveOrZero @Max(MAX_POISHA) Long price,
            @PositiveOrZero @Max(MAX_POISHA) Long costPrice,
            Long version) {
    }

    /** Replaces the options and rebuilds the variant grid (see ProductService#replaceOptions). */
    @PutMapping("/{id}/options")
    ProductViews.Detail replaceOptions(@PathVariable UUID id, @Valid @RequestBody OptionsRequest body) {
        return products.replaceOptions(id, inputs(body.options()), body.price(), body.costPrice(), body.version());
    }

    /** An uploaded file ({@code POST /uploads}), for the whole product or one variant. Up to 8. */
    record ImageRequest(@NotNull UUID fileId, UUID variantId) {
    }

    @PostMapping("/{id}/images")
    ProductViews.Detail addImage(@PathVariable UUID id, @Valid @RequestBody ImageRequest body) {
        return images.add(id, body.fileId(), body.variantId());
    }

    /** {@code variantId} null shows the image for the whole product. */
    record ImageVariantRequest(UUID variantId) {
    }

    @PatchMapping("/{id}/images/{imageId}")
    ProductViews.Detail showImageFor(@PathVariable UUID id, @PathVariable UUID imageId,
            @RequestBody ImageVariantRequest body) {
        return images.showFor(id, imageId, body.variantId());
    }

    record ImageOrderRequest(@NotNull @Size(max = 8) List<@NotNull UUID> ids) {
    }

    @PutMapping("/{id}/images/order")
    ProductViews.Detail reorderImages(@PathVariable UUID id, @Valid @RequestBody ImageOrderRequest body) {
        return images.reorder(id, body.ids());
    }

    /** The uploaded file itself is kept. */
    @DeleteMapping("/{id}/images/{imageId}")
    ProductViews.Detail removeImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        return images.remove(id, imageId);
    }

    private static List<ProductService.OptionInput> inputs(List<OptionRequest> options) {
        return options == null ? List.of()
                : options.stream().map(o -> new ProductService.OptionInput(o.id(), o.name(), o.values())).toList();
    }
}
