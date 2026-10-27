package com.mojud.orders;

/** backend-plan.md §6. Only OrderTransitions (7b) moves an order between them. */
public enum OrderStatus {
    DRAFT, CONFIRMED, PACKED, SHIPPED, DELIVERED, RETURNED, CANCELLED
}
