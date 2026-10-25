package com.mojud.tenancy.membership;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserId(UUID userId);

    /** The user's membership, if it is active and the business is active (not suspended). */
    @Query("""
            select m from Membership m
            where m.userId = :userId
              and m.status = com.mojud.tenancy.membership.MembershipStatus.ACTIVE
              and exists (select 1 from Business b
                          where b.id = m.businessId and b.status = com.mojud.tenancy.business.BusinessStatus.ACTIVE)""")
    Optional<Membership> findActive(UUID userId);

    /**
     * Records a switch to this business. A plain update rather than an entity change, so it does not
     * bump the membership's version: switching never conflicts with an owner changing the role.
     */
    @Modifying
    @Query("update Membership m set m.lastUsedAt = :now where m.id = :id")
    void markUsed(UUID id, Instant now);
}
