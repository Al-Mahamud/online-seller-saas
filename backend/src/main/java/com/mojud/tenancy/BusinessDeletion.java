package com.mojud.tenancy;

import java.util.UUID;

/**
 * Deletes a business and everything it owns, at once (the owner's deletion after its undo window,
 * or a read-only business past 90 days). The owner's account stays.
 */
public interface BusinessDeletion {

    /** @return the rows deleted */
    int deleteNow(UUID businessId);
}
