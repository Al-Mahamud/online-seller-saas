package com.mojud.customers;

import java.util.Optional;
import java.util.UUID;

/** Customers for other modules (orders), in the business of the current request. */
public interface CustomerDirectory {

    /** {@code phone} in E.164; the address fields may be null or empty. */
    record Snapshot(UUID id, String name, String phone, String address, Integer districtId, Integer areaId) {
    }

    Optional<Snapshot> find(UUID id);

    /**
     * The customer with this phone number (written any way), else a new one with these details.
     * An existing customer's details are left as they are.
     *
     * @throws com.mojud.shared.error.ApiException a validation error for an unusable name, phone or place
     */
    Snapshot findOrCreate(String name, String phone, String address, Integer districtId, Integer areaId);
}
