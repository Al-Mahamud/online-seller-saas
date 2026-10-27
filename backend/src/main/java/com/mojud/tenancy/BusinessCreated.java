package com.mojud.tenancy;

import java.util.UUID;

/**
 * Published inside the transaction that creates a business, with its TenantScope open, so other
 * modules can set up their own defaults (delivery zones) in the same transaction.
 */
public record BusinessCreated(UUID businessId, UUID ownerId) {
}
