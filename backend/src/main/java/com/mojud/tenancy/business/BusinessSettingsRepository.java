package com.mojud.tenancy.business;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Keyed by business id; only lookups by that id, for the same reason as BusinessRepository. */
interface BusinessSettingsRepository extends Repository<BusinessSettings, UUID> {

    Optional<BusinessSettings> findById(UUID businessId);

    BusinessSettings save(BusinessSettings settings);

    BusinessSettings saveAndFlush(BusinessSettings settings);
}
