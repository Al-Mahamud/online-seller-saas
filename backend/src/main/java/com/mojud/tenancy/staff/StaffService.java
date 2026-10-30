package com.mojud.tenancy.staff;

import com.mojud.identity.PasswordConfirmation;
import com.mojud.identity.UserDirectory;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.business.OwnedBusinesses;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import com.mojud.tenancy.membership.MembershipStatus;
import com.mojud.tenancy.system.MembershipDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The business's members (implementation-plan.md 2b). Changes count from the member's next request,
 * because the tenancy filter reads the membership on every request.
 *
 * <p>Rules: the owner is changed only by transferring ownership; nobody changes their own role or
 * removes themselves; roles given here are MANAGER or STAFF.
 */
@Service
class StaffService {

    record StaffMember(UUID userId, String name, String phone, @Nullable String email, BusinessRole role, Instant joinedAt,
            boolean you) {
    }

    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final PasswordConfirmation passwordConfirmation;
    private final TransactionTemplate transactions;
    private final InviteRepository invites;
    private final MembershipDirectory directory;
    private final AdvisoryLocks locks;
    private final AuditTrail audit;
    private final Clock clock;
    private final int maxOwnedBusinesses;

    StaffService(MembershipRepository memberships, UserDirectory users, PasswordConfirmation passwordConfirmation,
            TransactionTemplate transactions, InviteRepository invites, MembershipDirectory directory,
            AdvisoryLocks locks, AuditTrail audit, Clock clock,
            @Value("${mojud.tenancy.max-owned-businesses}") int maxOwnedBusinesses) {
        this.invites = invites;
        this.directory = directory;
        this.locks = locks;
        this.audit = audit;
        this.maxOwnedBusinesses = maxOwnedBusinesses;
        this.memberships = memberships;
        this.users = users;
        this.passwordConfirmation = passwordConfirmation;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<StaffMember> list() {
        UUID me = CurrentBusiness.require().userId();
        List<Membership> active = memberships.findAllByStatusOrderByJoinedAt(MembershipStatus.ACTIVE);
        Map<UUID, UserDirectory.UserSummary> people = users.summaries(active.stream().map(Membership::getUserId).toList());
        return active.stream().map(member -> {
            UserDirectory.UserSummary person = people.get(member.getUserId());
            return new StaffMember(member.getUserId(), person.name(), person.phone(), person.email(), member.getRole(),
                    member.getJoinedAt(), member.getUserId().equals(me));
        }).toList();
    }

    /** Demoting to STAFF also withdraws the member's unanswered invites: staff cannot invite. */
    @Transactional
    void changeRole(UUID userId, BusinessRole role) {
        if (role == BusinessRole.OWNER) {
            throw ApiException.invalidField("role", "field.invalid_value");
        }
        Membership member = otherMember(userId);
        BusinessRole before = member.getRole();
        member.changeRole(role, clock.instant());
        int withdrawn = role == BusinessRole.STAFF ? invites.revokeAllSentBy(userId, clock.instant()) : 0;
        if (before != role) {
            CurrentBusiness business = CurrentBusiness.require();
            audit.record(business.businessId(), business.userId(), "staff.role_changed", "user", userId,
                    "from", before, "to", role, "invitesWithdrawn", withdrawn);
        }
    }

    /** Also withdraws the member's unanswered invites, so nobody can rejoin through them. */
    @Transactional
    void remove(UUID userId) {
        Membership member = otherMember(userId);
        member.remove(clock.instant());
        int withdrawn = invites.revokeAllSentBy(userId, clock.instant());
        CurrentBusiness business = CurrentBusiness.require();
        audit.record(business.businessId(), business.userId(), "staff.removed", "user", userId,
                "role", member.getRole(), "invitesWithdrawn", withdrawn);
    }

    /** As being removed, by the member themself. The owner hands the business over first. */
    @Transactional
    void leave() {
        CurrentBusiness business = CurrentBusiness.require();
        Membership member = memberships.findByUserId(business.userId()).filter(Membership::isActive)
                .orElseThrow(ApiException::notFound);
        if (member.getRole() == BusinessRole.OWNER) {
            throw new ApiException(ErrorCode.STAFF_OWNER_PROTECTED);
        }
        member.remove(clock.instant());
        int withdrawn = invites.revokeAllSentBy(business.userId(), clock.instant());
        audit.record(business.businessId(), business.userId(), "staff.left", "user", business.userId(),
                "role", member.getRole(), "invitesWithdrawn", withdrawn);
    }

    /**
     * Hands the business to another member, who becomes OWNER; the current owner becomes MANAGER.
     * Needs the owner's password. The caller's membership is locked and checked again inside the
     * transaction, so a repeated or parallel transfer finds they are no longer the owner. The new
     * owner's businesses are counted under the same lock as creating one, so the owner limit holds.
     */
    void transferOwnership(UUID newOwnerId, String password, String clientIp) {
        CurrentBusiness current = CurrentBusiness.require();
        passwordConfirmation.confirm(current.userId(), password, clientIp);
        transactions.executeWithoutResult(status -> {
            Membership oldOwner = memberships.findByUserIdForUpdate(current.userId())
                    .filter(member -> member.isActive() && member.getRole() == BusinessRole.OWNER)
                    .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
            Membership newOwner = otherMember(newOwnerId);
            locks.lockForTransaction(OwnedBusinesses.lockKey(newOwnerId));
            if (directory.ownedBusinessCount(newOwnerId) >= maxOwnedBusinesses) {
                throw new ApiException(ErrorCode.STAFF_OWNER_LIMIT, maxOwnedBusinesses);
            }
            Instant now = clock.instant();
            // Demote first: the database allows one active owner per business at any moment.
            oldOwner.changeRole(BusinessRole.MANAGER, now);
            memberships.saveAndFlush(oldOwner);
            newOwner.changeRole(BusinessRole.OWNER, now);
            memberships.saveAndFlush(newOwner);
            audit.record(current.businessId(), current.userId(), "business.ownership_transferred", "user", newOwnerId,
                    "from", current.userId(), "to", newOwnerId);
        });
    }

    /** Another active member who is not the owner. */
    private Membership otherMember(UUID userId) {
        if (userId.equals(CurrentBusiness.require().userId())) {
            throw new ApiException(ErrorCode.STAFF_SELF_CHANGE);
        }
        Membership member = memberships.findByUserId(userId).filter(Membership::isActive)
                .orElseThrow(ApiException::notFound);
        if (member.getRole() == BusinessRole.OWNER) {
            throw new ApiException(ErrorCode.STAFF_OWNER_PROTECTED);
        }
        return member;
    }
}
