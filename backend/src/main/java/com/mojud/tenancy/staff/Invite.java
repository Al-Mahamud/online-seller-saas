package com.mojud.tenancy.staff;

import com.mojud.shared.id.Ids;
import com.mojud.tenancy.BusinessRole;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * An invitation to join the business. Open until accepted, revoked or expired. Only a user who has
 * verified {@link #destination} may accept it, so a forwarded link is useless to anyone else.
 */
@Entity
@Table(name = "invites")
class Invite {

    @Id
    private UUID id;

    @TenantId
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    private InviteChannel channel;

    private String destination;

    @Enumerated(EnumType.STRING)
    private BusinessRole role;

    private String tokenHash;

    private UUID invitedBy;

    private Instant expiresAt;

    private Instant acceptedAt;

    private UUID acceptedBy;

    private Instant revokedAt;

    private Instant createdAt;

    @Version
    private Long version;

    protected Invite() {
    }

    Invite(InviteChannel channel, String destination, BusinessRole role, String tokenHash, UUID invitedBy,
            Instant expiresAt, Instant now) {
        this.id = Ids.newId();
        this.channel = channel;
        this.destination = destination;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    boolean isOpen(Instant now) {
        return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    void revoke(Instant now) {
        revokedAt = now;
    }

    void accept(UUID userId, Instant now) {
        acceptedAt = now;
        acceptedBy = userId;
    }

    UUID getId() {
        return id;
    }

    InviteChannel getChannel() {
        return channel;
    }

    String getDestination() {
        return destination;
    }

    BusinessRole getRole() {
        return role;
    }

    UUID getInvitedBy() {
        return invitedBy;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}
