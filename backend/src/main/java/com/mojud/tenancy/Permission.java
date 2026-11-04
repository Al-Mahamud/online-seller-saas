package com.mojud.tenancy;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a member may do in a business, by area. The owner may do everything; what staff and
 * managers may do, each business decides ({@code business_settings}), starting from
 * {@link #defaults}, the rules from before it could.
 */
public enum Permission {
    /** Take, edit, pack and send orders; slips and shipments. */
    ORDERS,
    /** Stock changes, damaged goods, what's running low. */
    STOCK,
    /** Add and edit customers (finding one while taking an order needs only {@link #ORDERS}). */
    CUSTOMERS,
    /** See what things cost and the profit. */
    COSTS,
    /** Add and change products, prices, variants, categories and tags; import. */
    PRODUCTS,
    /** The business's details, delivery charges, notification choices; see the plan. */
    SETTINGS,
    /** Add, change and remove members, never above one's own role. */
    TEAM;

    public static Set<Permission> all() {
        return EnumSet.allOf(Permission.class);
    }

    /** The rules before a business changes them. */
    public static Set<Permission> defaults(BusinessRole role) {
        return role == BusinessRole.STAFF ? EnumSet.of(ORDERS, STOCK, CUSTOMERS) : all();
    }
}
