/**
 * The append-only stock ledger and the cached stock levels built from it; the only module that
 * changes stock (Phase 4). Other modules use {@link com.mojud.inventory.StockService} to change
 * stock inside their own transactions and {@link com.mojud.inventory.StockQueries} to read it.
 */
package com.mojud.inventory;
