package com.mojud.tenancy.business;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Only lookups by the business's own id. A business's id is its tenant key, so Hibernate's
 * {@code @TenantId} filter cannot cover this table; offering no query that could return several
 * businesses (no {@code findAll}) is the application-side guard, and row-level security the
 * database-side one. BusinessRepositoryShapeTest keeps it that way.
 */
interface BusinessRepository extends Repository<Business, UUID> {

    Optional<Business> findById(UUID id);

    Business saveAndFlush(Business business);
}
