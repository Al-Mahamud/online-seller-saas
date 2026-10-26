package com.mojud.tenancy.staff;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Scoped to the current business by Hibernate ({@code @TenantId}) and by row-level security. */
interface InviteRepository extends JpaRepository<Invite, UUID> {

    /** For reading only, such as the public preview: takes no lock. */
    Optional<Invite> findByTokenHash(String tokenHash);

    /** Locked, so two people cannot accept the same invite at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invite i where i.tokenHash = :tokenHash")
    Optional<Invite> findByTokenHashForUpdate(String tokenHash);

    /** The open invite to this address, expired or not (a new invite replaces it). */
    @Query("select i from Invite i where i.destination = :destination and i.acceptedAt is null and i.revokedAt is null")
    Optional<Invite> findUnanswered(String destination);

    @Query("""
            select i from Invite i
            where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now
            order by i.createdAt desc""")
    List<Invite> findOpen(Instant now);

    /** Withdraws everything this member invited and nobody has answered yet. */
    @Modifying
    @Query("""
            update Invite i set i.revokedAt = :now
            where i.invitedBy = :userId and i.acceptedAt is null and i.revokedAt is null""")
    int revokeAllSentBy(UUID userId, Instant now);
}
