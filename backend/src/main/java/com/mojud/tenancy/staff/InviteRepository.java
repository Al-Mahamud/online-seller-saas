package com.mojud.tenancy.staff;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface InviteRepository extends JpaRepository<Invite, UUID> {

    /** Locked, so two people cannot accept the same invite at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invite> findByTokenHash(String tokenHash);

    /** The open invite to this address, expired or not (a new invite replaces it). */
    @Query("select i from Invite i where i.destination = :destination and i.acceptedAt is null and i.revokedAt is null")
    Optional<Invite> findUnanswered(String destination);

    @Query("""
            select i from Invite i
            where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now
            order by i.createdAt desc""")
    List<Invite> findOpen(Instant now);
}
