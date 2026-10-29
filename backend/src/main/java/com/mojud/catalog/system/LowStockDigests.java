package com.mojud.catalog.system;

import java.util.UUID;

/** Emails one business's managers and owners what is running low, if anything is. */
public interface LowStockDigests {

    /** @return how many members it went to */
    int send(UUID businessId);
}
