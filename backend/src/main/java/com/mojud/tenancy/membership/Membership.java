package com.mojud.tenancy.membership;

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

@Entity
@Table(name = "memberships")
public class Membership {

    @Id
    private UUID id;

    /** Filled from the current TenantScope on insert and added to every query by Hibernate. */
    @TenantId
    private UUID businessId;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private BusinessRole role;

    @Enumerated(EnumType.STRING)
    private MembershipStatus status;

    private Instant lastUsedAt;

    /** When the member joined, or rejoined after being removed. */
    private Instant joinedAt;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version;

    protected Membership() {
    }

    /** A new active member of the business in the current TenantScope. */
    public Membership(UUID userId, BusinessRole role, Instant now) {
        this.id = Ids.newId();
        this.userId = userId;
        this.role = role;
        this.status = MembershipStatus.ACTIVE;
        this.lastUsedAt = now;
        this.joinedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** OWNER only through ownership transfer (StaffService). */
    public void changeRole(BusinessRole newRole, Instant now) {
        role = newRole;
        updatedAt = now;
    }

    public void remove(Instant now) {
        status = MembershipStatus.REMOVED;
        updatedAt = now;
    }

    /** A former member accepted a new invite. */
    public void rejoin(BusinessRole newRole, Instant now) {
        status = MembershipStatus.ACTIVE;
        role = newRole;
        lastUsedAt = now;
        joinedAt = now;
        updatedAt = now;
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public BusinessRole getRole() {
        return role;
    }
}
