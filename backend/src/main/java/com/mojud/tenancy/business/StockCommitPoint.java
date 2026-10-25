package com.mojud.tenancy.business;

/** When an order's items leave the shelf count (backend-plan.md §6, agreed answer 13.1). */
public enum StockCommitPoint {
    /** Reserved at confirm, deducted when handed to the courier (the default). */
    SHIP,
    /** Deducted at confirm; there is no reservation step. */
    CONFIRM
}
