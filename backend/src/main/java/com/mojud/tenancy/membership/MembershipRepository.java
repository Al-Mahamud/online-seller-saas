package com.mojud.tenancy.membership;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserId(UUID userId);
}
