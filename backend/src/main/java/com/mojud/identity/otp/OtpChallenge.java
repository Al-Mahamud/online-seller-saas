package com.mojud.identity.otp;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "otp_challenges")
class OtpChallenge {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private OtpChannel channel;

    private String destination;

    @Enumerated(EnumType.STRING)
    private OtpPurpose purpose;

    private String codeHash;

    private short attempts;

    private Instant expiresAt;

    private Instant consumedAt;

    private Instant createdAt;

    protected OtpChallenge() {
    }

    OtpChallenge(UUID id, OtpDestination destination, OtpPurpose purpose, String codeHash, Instant expiresAt,
            Instant createdAt) {
        this.id = id;
        this.channel = destination.channel();
        this.destination = destination.value();
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    boolean isUsable(Instant now, int maxAttempts) {
        return consumedAt == null && now.isBefore(expiresAt) && attempts < maxAttempts;
    }

    void recordFailedAttempt() {
        attempts++;
    }

    void consume(Instant now) {
        consumedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getCodeHash() {
        return codeHash;
    }
}
