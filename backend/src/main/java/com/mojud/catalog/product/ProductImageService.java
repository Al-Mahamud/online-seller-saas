package com.mojud.catalog.product;

import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Images on a product (implementation-plan.md 3c): up to {@value #MAX_IMAGES}, in order, each for
 * the whole product or one of its variants. Changes to one product's images take turns, so the
 * limit and positions hold. Removing an image keeps the uploaded file.
 */
@Service
class ProductImageService {

    static final int MAX_IMAGES = 8;

    private static final Map<String, ErrorCode> CONSTRAINT_ERRORS = Map.of(
            // Another business's (or a missing) file or variant looks like one that does not exist.
            "fk_product_images_file", ErrorCode.NOT_FOUND,
            "fk_product_images_variant", ErrorCode.NOT_FOUND);

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final ProductImageRepository images;
    private final ProductService productService;
    private final TransactionTemplate transactions;
    private final AdvisoryLocks locks;
    private final Clock clock;

    ProductImageService(ProductRepository products, ProductVariantRepository variants, ProductImageRepository images,
            ProductService productService, TransactionTemplate transactions, AdvisoryLocks locks, Clock clock) {
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.productService = productService;
        this.transactions = transactions;
        this.locks = locks;
        this.clock = clock;
    }

    /** Added last. Adding a file the product already shows changes nothing. */
    ProductViews.Detail add(UUID productId, UUID fileId, UUID variantId) {
        return changing(productId, () -> {
            // Checked first, so a wrong variant is refused even when the file is already shown.
            requireVariantOf(productId, variantId);
            List<ProductImage> current = images.findByProductIdOrderByPosition(productId);
            if (current.stream().noneMatch(image -> image.getFileId().equals(fileId))) {
                if (current.size() >= MAX_IMAGES) {
                    throw new ApiException(ErrorCode.PRODUCT_IMAGE_LIMIT);
                }
                images.save(new ProductImage(productId, fileId, variantId,
                        current.isEmpty() ? 0 : current.get(current.size() - 1).getPosition() + 1, clock.instant()));
                images.flush();
            }
        });
    }

    /** {@code variantId} null shows the image for the whole product. */
    ProductViews.Detail showFor(UUID productId, UUID imageId, UUID variantId) {
        return changing(productId, () -> {
            requireVariantOf(productId, variantId);
            image(productId, imageId).showFor(variantId);
            images.flush();
        });
    }

    /** {@code ids} must list each of the product's images once. */
    ProductViews.Detail reorder(UUID productId, List<UUID> ids) {
        return changing(productId, () -> {
            List<ProductImage> current = images.findByProductIdOrderByPosition(productId);
            Set<UUID> given = new HashSet<>(ids);
            if (given.size() != ids.size() || !given.equals(new HashSet<>(current.stream().map(ProductImage::getId).toList()))) {
                throw ApiException.invalidField("ids", "field.order_ids");
            }
            current.forEach(image -> image.placeAt(ids.indexOf(image.getId())));
            images.flush();
        });
    }

    ProductViews.Detail remove(UUID productId, UUID imageId) {
        return changing(productId, () -> {
            images.delete(image(productId, imageId));
            images.flush();
        });
    }

    private ProductViews.Detail changing(UUID productId, Runnable work) {
        Supplier<Object> inTransaction = () -> {
            products.findById(productId).orElseThrow(ApiException::notFound);
            locks.lockForTransaction("product-images:" + productId);
            work.run();
            return null;
        };
        Constraints.translating(transactions, CONSTRAINT_ERRORS, inTransaction);
        return productService.get(productId);
    }

    private ProductImage image(UUID productId, UUID imageId) {
        return images.findById(imageId).filter(image -> image.getProductId().equals(productId))
                .orElseThrow(ApiException::notFound);
    }

    private void requireVariantOf(UUID productId, UUID variantId) {
        if (variantId != null && variants.findById(variantId).filter(v -> v.getProductId().equals(productId)).isEmpty()) {
            throw ApiException.notFound();
        }
    }
}
