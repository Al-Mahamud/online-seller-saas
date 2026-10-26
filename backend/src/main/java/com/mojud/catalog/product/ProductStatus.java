package com.mojud.catalog.product;

/** Products and variants are archived, never deleted: orders and stock history point at them. */
enum ProductStatus {
    ACTIVE,
    ARCHIVED
}
