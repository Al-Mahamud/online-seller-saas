package com.mojud.tenancy;

import java.util.UUID;

/**
 * Published once a business and all its rows are gone (BusinessDeletion), so modules can remove
 * what lives outside the database, such as its files.
 */
public record BusinessDeleted(UUID businessId) {
}
