package com.mojud.identity.otp;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    /**
     * Locks the row until the transaction ends, so parallel guesses at one code are checked one
     * after another: each sees the attempts counted before it, and a right code works only once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OtpChallenge> findFirstByDestinationAndPurposeOrderByCreatedAtDesc(String destination, OtpPurpose purpose);
}
