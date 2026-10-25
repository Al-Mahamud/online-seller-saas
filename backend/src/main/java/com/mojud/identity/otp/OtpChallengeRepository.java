package com.mojud.identity.otp;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * The lookups lock the row until the transaction ends, so parallel guesses at one code are checked
 * one after another: each sees the attempts counted before it, and a right code works only once.
 */
interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    /** The newest code for codes that belong to no account yet (sign-up, sign-in, reset). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from OtpChallenge c
            where c.destination = :destination and c.purpose = :purpose and c.requestedBy is null
            order by c.createdAt desc limit 1""")
    Optional<OtpChallenge> findNewest(String destination, OtpPurpose purpose);

    /** The newest code this user asked for: other users' codes to the same address never count. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from OtpChallenge c
            where c.destination = :destination and c.purpose = :purpose and c.requestedBy = :requestedBy
            order by c.createdAt desc limit 1""")
    Optional<OtpChallenge> findNewestFor(String destination, OtpPurpose purpose, UUID requestedBy);
}
