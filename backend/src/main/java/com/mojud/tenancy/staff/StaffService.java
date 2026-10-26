package com.mojud.tenancy.staff;

import com.mojud.identity.PasswordConfirmation;
import com.mojud.identity.UserDirectory;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import com.mojud.tenancy.membership.MembershipStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    record StaffMember(UUID userId, String name, String phone, String email, BusinessRole role, Instant joinedAt,
            boolean you) {
    }

    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final PasswordConfirmation passwordConfirmation;
    private final TransactionTemplate transactions;
    private final Clock clock;

    StaffService(MembershipRepository memberships, UserDirectory users, PasswordConfirmation passwordConfirmation,
            TransactionTemplate transactions, Clock clock) {
        this.memberships = memberships;
        this.users = users;
        this.passwordConfirmation = passwordConfirmation;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<StaffMember> list() {
        UUID me = CurrentBusiness.require().userId();
        List<Membership> active = memberships.findAllByStatusOrderByCreatedAt(MembershipStatus.ACTIVE);
        Map<UUID, UserDirectory.UserSummary> people = users.summaries(active.stream().map(Membership::getUserId).toList());
        return active.stream().map(member -> {
            UserDirectory.UserSummary person = people.get(member.getUserId());
            return new StaffMember(member.getUserId(), person.name(), person.phone(), person.email(), member.getRole(),
                    member.getCreatedAt(), member.getUserId().equals(me));
        }).toList();
    }

    @Transactional
    void changeRole(UUID userId, BusinessRole role) {
        if (role == BusinessRole.OWNER) {
            throw ApiException.invalidField("role", "field.invalid_value");
        }
        otherMember(userId).changeRole(role, clock.instant());
    }

    @Transactional
    void remove(UUID userId) {
        otherMember(userId).remove(clock.instant());
    }

    /**
     * Hands the business to another member, who becomes OWNER; the current owner becomes MANAGER.
     * Needs the owner's password.
     */
    void transferOwnership(UUID newOwnerId, String password, String clientIp) {
        CurrentBusiness current = CurrentBusiness.require();
        passwordConfirmation.confirm(current.userId(), password, clientIp);
        transactions.executeWithoutResult(status -> {
            Membership newOwner = otherMember(newOwnerId);
            Membership oldOwner = memberships.findByUserId(current.userId()).orElseThrow(ApiException::notFound);
            Instant now = clock.instant();
            // Demote first: the database allows one active owner per business at any moment.
            oldOwner.changeRole(BusinessRole.MANAGER, now);
            memberships.saveAndFlush(oldOwner);
            newOwner.changeRole(BusinessRole.OWNER, now);
            memberships.saveAndFlush(newOwner);
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
