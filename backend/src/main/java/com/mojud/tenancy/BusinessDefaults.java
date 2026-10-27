package com.mojud.tenancy;

/**
 * Business settings other modules need, for the business being worked in (its TenantScope), so
 * jobs without a signed-in member can use them too.
 */
public interface BusinessDefaults {

    /** The alert level for variants that have none of their own. */
    int lowStockDefault();

    /** Put before order numbers: "RK-" makes order 1001 "RK-1001"; empty shows "#1001". */
    String orderNumberPrefix();

    /** True when confirming an order takes its stock (stock_commit_point CONFIRM); else shipping does. */
    boolean deductsStockAtConfirm();
}
