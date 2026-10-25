package com.mojud.tenancy.business;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Row-level security limits every query to the business of the current TenantScope. */
interface BusinessRepository extends JpaRepository<Business, UUID> {
}
