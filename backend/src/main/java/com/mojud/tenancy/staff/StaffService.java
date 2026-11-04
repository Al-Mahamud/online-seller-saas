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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The business's members (implementation-plan.md 2b). Changes count from the member's next request,
 * because the tenancy filter reads the membership on every request.
 *
 * <p>Rules: the owner is changed only by transferring ownership; nobody changes their own role or
 * removes themselves; roles given here are MANAGER or STAFF; and a member allowed to manage the team
 * ({@code TEAM}) never manages, or gives, a role above their own.
 */
@Service
class StaffService {

    /**
     * {@code loginId}: the User ID they sign in with. {@code awaitingFirstSignIn}: they still have the
     * starting password. {@code canSendPassword}: the caller may send them a new starting password.
     */
    record StaffMember(UUID userId, String name, String phone, @Nullable String email, BusinessRole role, Instant joinedAt,
            boolean you, String loginId, boolean awaitingFirstSignIn, boolean canSendPassword) {
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

    StaffService(MembershipRepository memberships, UserDirectory users, PasswordConfirmation passwordConfirmation,
            TransactionTemplate transactions, InviteRepository invites, MembershipDirectory directory,
            AdvisoryLocks locks, AuditTrail audit, Clock clock) {
        this.invites = invites;
        this.directory = directory;
        this.locks = locks;
        this.audit = audit;
        this.memberships = memberships;
        this.users = users;
        this.passwordConfirmation = passwordConfirmation;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<StaffMember> list() {
        List<Membership> active = memberships.findAllByStatusOrderByJoinedAt(MembershipStatus.ACTIVE);
        Map<UUID, UserDirectory.UserSummary> people = users.summaries(active.stream().map(Membership::getUserId).toList());
        return active.stream().map(member -> member(member, people.get(member.getUserId()))).toList();
    }

    StaffMember member(Membership member, UserDirectory.UserSummary person) {
        return member(member, person, directory.activeMembershipCount(member.getUserId()));
    }

    /** {@code businesses}: how many businesses they work in, counted by the caller when it knows better. */
    StaffMember member(Membership member, UserDirectory.UserSummary person, int businesses) {
        CurrentBusiness business = CurrentBusiness.require();
        boolean you = member.getUserId().equals(business.userId());
        return new StaffMember(member.getUserId(), person.name(), person.phone(), person.email(), member.getRole(),
                member.getJoinedAt(), you, person.loginId(), person.awaitingFirstSignIn(),
                !you && member.getRole() != BusinessRole.OWNER && mayManage(business, member.getRole())
                        && business.businessId().equals(person.createdForBusiness())
                        && businesses == 1);
    }

    /** The owner manages anyone; others only roles up to their own. */
    static boolean mayManage(CurrentBusiness actor, BusinessRole role) {
        return actor.role() == BusinessRole.OWNER || actor.role().atLeast(role);
    }

    static void requireMayManage(CurrentBusiness actor, BusinessRole role) {
        if (!mayManage(actor, role)) {
            throw new ApiException(ErrorCode.STAFF_ROLE_ABOVE_YOURS);
        }
    }

    /** Demoting to STAFF also withdraws the member's unanswered invites: staff cannot invite. */
    @Transactional
    void changeRole(UUID userId, BusinessRole role) {
        if (role == BusinessRole.OWNER) {
            throw ApiException.invalidField("role", "field.invalid_value");
        }
        Membership member = otherMember(userId);
        BusinessRole before = member.getRole();
        requireMayManage(CurrentBusiness.require(), before);
        requireMayManage(CurrentBusiness.require(), role);
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
        requireMayManage(CurrentBusiness.require(), member.getRole());
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
     * transaction, so a repeated or parallel transfer finds they are no longer the owner. Someone who
     * already owns a business can't receive another ({@link OwnedBusinesses}), checked under the
     * same lock as creating one.
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
            if (directory.ownedBusinessCount(newOwnerId) > 0) {
                throw new ApiException(ErrorCode.STAFF_OWNER_LIMIT);
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
    Membership otherMember(UUID userId) {
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
