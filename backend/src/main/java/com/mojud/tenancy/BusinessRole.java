package com.mojud.tenancy;

/**
 * A member's role in one business (backend-plan.md §7). Ordered from most to least powerful, so
 * {@link #atLeast} compares them.
 */
public enum BusinessRole {
    /** Billing, staff, settings; one per business. */
    OWNER,
    /** Everything except billing and ownership. */
    MANAGER,
    /** Orders, customers, stock adjustments; never sees cost prices. */
    STAFF;

    public boolean atLeast(BusinessRole required) {
        return ordinal() <= required.ordinal();
    }
}
