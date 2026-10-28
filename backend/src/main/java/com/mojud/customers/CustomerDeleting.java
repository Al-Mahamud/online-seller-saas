package com.mojud.customers;

import java.util.UUID;

/**
 * Published just before a customer is deleted, in that transaction (implementation-plan.md 12b):
 * modules holding copies of the customer's details remove them, or refuse (and so stop the
 * deletion) while the customer is still needed.
 */
public record CustomerDeleting(UUID customerId) {
}
