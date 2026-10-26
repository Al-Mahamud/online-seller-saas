package com.mojud.tenancy;

/**
 * Business settings other modules need, for the business being worked in (its TenantScope), so
 * jobs without a signed-in member can use them too.
 */
public interface BusinessDefaults {

    /** The alert level for variants that have none of their own. */
    int lowStockDefault();
}
