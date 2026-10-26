package com.mojud.catalog.product;

import java.util.UUID;

/** A tag as shown on a product. */
record TagRef(UUID productId, UUID id, String name) {
}
