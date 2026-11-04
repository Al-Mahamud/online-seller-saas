package com.mojud.tenancy.staff;

import com.mojud.identity.TeamAccounts;
import com.mojud.identity.UserDirectory;
import com.mojud.shared.async.AfterCommitTasks;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.email.EmailComposer;
import com.mojud.shared.email.EmailMessage;
import com.mojud.shared.email.EmailSender;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.sms.SmsSender;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.SeatPolicy;
import com.mojud.tenancy.business.BusinessNames;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import com.mojud.tenancy.system.MembershipDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adding members without invite links: the business creates the person's account, with a User ID
 * it chose and a starting password shown once to it and sent to the person's phone (or email).
 * Someone whose phone or email already has a verified Mojud account is added with that account,
 * and told so; no new password is made, and their User ID is not shown.
 *
 * <p>A member allowed to manage the team never adds a role above their own (StaffService).
 */
@Service
class TeamMembers {

    /** How the details went out: by SMS, by email, or not at all (no sender is set up). */
    enum SentBy { SMS, EMAIL }

    /** {@code startingPassword}: only for a new account, and shown only in this answer. */
    record Added(StaffService.StaffMember member, @Nullable String startingPassword, boolean existingAccount,
            @Nullable SentBy sentBy) {
    }

    record NewPassword(String startingPassword, @Nullable SentBy sentBy) {
    }

    private final MembershipRepository memberships;
    private final MembershipDirectory directory;
    private final UserDirectory users;
    private final TeamAccounts accounts;
    private final StaffService staff;
    private final BusinessNames businessNames;
    private final SeatPolicy seats;
    private final TeamCounts team;
    private final ObjectProvider<SmsSender> sms;
    private final ObjectProvider<EmailSender> email;
    private final EmailComposer composer;
    private final AfterCommitTasks afterCommit;
    private final MessageSource messages;
    private final TransactionTemplate transactions;
    private final AdvisoryLocks locks;
    private final AuditTrail audit;
    private final Clock clock;
    private final String webBaseUrl;

    TeamMembers(MembershipRepository memberships, MembershipDirectory directory, UserDirectory users,
            TeamAccounts accounts, StaffService staff, BusinessNames businessNames, SeatPolicy seats, TeamCounts team,
            ObjectProvider<SmsSender> sms, ObjectProvider<EmailSender> email, EmailComposer composer,
            AfterCommitTasks afterCommit, MessageSource messages, PlatformTransactionManager transactionManager,
            AdvisoryLocks locks, AuditTrail audit, Clock clock, @Value("${mojud.web.base-url}") String webBaseUrl) {
        this.memberships = memberships;
        this.directory = directory;
        this.users = users;
        this.accounts = accounts;
        this.staff = staff;
        this.businessNames = businessNames;
        this.seats = seats;
        this.team = team;
        this.sms = sms;
        this.email = email;
        this.composer = composer;
        this.afterCommit = afterCommit;
        this.messages = messages;
        this.transactions = new TransactionTemplate(transactionManager);
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl;
    }

    /**
     * @throws ApiException {@code staff.already_member}, {@code staff.role_above_yours}, a seat limit,
     *         or a validation error on {@code name}, {@code phone}, {@code email} or {@code userId}
     */
    Added add(String name, String phoneInput, @Nullable String emailInput, String userId, BusinessRole role) {
        CurrentBusiness business = CurrentBusiness.require();
        if (role == BusinessRole.OWNER) {
            throw ApiException.invalidField("role", "field.invalid_value");
        }
        StaffService.requireMayManage(business, role);
        PhoneNumber phone = PhoneNumber.fromInput(phoneInput, "phone");
        EmailAddress emailAddress = emailInput == null || emailInput.isBlank() ? null
                : EmailAddress.tryParse(emailInput).orElseThrow(() -> ApiException.invalidField("email", "field.email_invalid"));
        Locale locale = LocaleContextHolder.getLocale();
        return transactions.execute(status -> {
            // Seat changes take turns, so two additions at once cannot both find the last free seat.
            locks.lockForTransaction("seats:" + business.businessId());
            Optional<UUID> existing = users.findByVerifiedContact(phone.e164())
                    .or(() -> emailAddress == null ? Optional.empty() : users.findByVerifiedContact(emailAddress.value()));
            Optional<Membership> former = existing.flatMap(memberships::findByUserId);
            if (former.filter(Membership::isActive).isPresent()) {
                throw new ApiException(ErrorCode.STAFF_ALREADY_MEMBER);
            }
            seats.requireSeats(team.staffSeatsUsed() + 1);
            Instant now = clock.instant();
            UUID memberId;
            String password = null;
            if (existing.isPresent()) {
                memberId = existing.get();
            } else {
                TeamAccounts.Created created = accounts.create(userId, name, phone, emailAddress,
                        locale.getLanguage().equals("en") ? "en" : "bn", business.businessId());
                memberId = created.userId();
                password = created.startingPassword();
            }
            Membership membership = former.map(old -> {
                old.rejoin(role, now);
                return old;
            }).orElseGet(() -> memberships.save(new Membership(memberId, role, now)));
            memberships.flush();
            audit.record(business.businessId(), business.userId(), "staff.added", "user", memberId,
                    "role", role, "newAccount", existing.isEmpty());
            UserDirectory.UserSummary person = users.summaries(List.of(memberId)).get(memberId);
            SentBy sentBy = existing.isPresent()
                    ? sendAdded(business, role, phone, emailAddress, locale)
                    : sendCredentials(business, role, person.loginId(), password, phone, emailAddress, locale);
            // Counted from this transaction: the directory's own connection can't see the new membership yet.
            int businesses = existing.isPresent() ? directory.activeMembershipCount(memberId) + 1 : 1;
            return new Added(staff.member(membership, person, businesses), password, existing.isPresent(), sentBy);
        });
    }

    /**
     * A new starting password for a member whose account this business made and who works nowhere
     * else; their old password stops working and they are signed out everywhere.
     *
     * @throws ApiException {@code staff.password_not_yours}, {@code staff.role_above_yours}
     */
    NewPassword newPassword(UUID memberId) {
        CurrentBusiness business = CurrentBusiness.require();
        UserDirectory.UserSummary person = transactions.execute(status -> {
            Membership member = staff.otherMember(memberId);
            StaffService.requireMayManage(business, member.getRole());
            UserDirectory.UserSummary found = users.summaries(List.of(memberId)).get(memberId);
            if (!business.businessId().equals(found.createdForBusiness())
                    || directory.activeMembershipCount(memberId) != 1) {
                throw new ApiException(ErrorCode.STAFF_PASSWORD_NOT_YOURS);
            }
            return found;
        });
        String password = accounts.newStartingPassword(memberId, business.businessId())
                .orElseThrow(() -> new ApiException(ErrorCode.STAFF_PASSWORD_NOT_YOURS));
        Locale locale = LocaleContextHolder.getLocale();
        SentBy sentBy = transactions.execute(status -> {
            audit.record(business.businessId(), business.userId(), "staff.password_sent", "user", memberId);
            BusinessRole role = memberships.findByUserId(memberId).map(Membership::getRole).orElse(BusinessRole.STAFF);
            EmailAddress to = person.email() == null ? null : new EmailAddress(person.email());
            return sendCredentials(business, role, person.loginId(), password, new PhoneNumber(person.phone()), to,
                    locale);
        });
        return new NewPassword(password, sentBy);
    }

    /** By SMS when it can, else by email when there is one; null when neither can go out. */
    private @Nullable SentBy sendCredentials(CurrentBusiness business, BusinessRole role, String loginId,
            String password, PhoneNumber phone, @Nullable EmailAddress to, Locale locale) {
        Object[] args = {
            businessNames.nameOf(business.businessId()),
            messages.getMessage("role." + role, null, locale),
            loginId,
            password,
            webBaseUrl + "/sign-in",
            TeamAccounts.STARTING_PASSWORD_LIFETIME.toDays()
        };
        SmsSender smsSender = sms.getIfAvailable();
        if (smsSender != null) {
            String text = messages.getMessage("team.credentials.sms", args, locale);
            afterCommit.run(() -> smsSender.send(phone, text));
            return SentBy.SMS;
        }
        EmailSender emailSender = email.getIfAvailable();
        if (emailSender != null && to != null) {
            EmailMessage message = composer.in(locale)
                    .subject("email.team.credentials.subject", args)
                    .paragraph("email.team.credentials.intro", args)
                    .action("email.team.credentials.action", webBaseUrl + "/sign-in")
                    .note("email.team.credentials.expiry", args[5])
                    .build();
            afterCommit.run(() -> emailSender.send(to, message));
            return SentBy.EMAIL;
        }
        return null;
    }

    private @Nullable SentBy sendAdded(CurrentBusiness business, BusinessRole role, PhoneNumber phone,
            @Nullable EmailAddress to, Locale locale) {
        Object[] args = {
            businessNames.nameOf(business.businessId()),
            messages.getMessage("role." + role, null, locale),
            webBaseUrl + "/sign-in"
        };
        SmsSender smsSender = sms.getIfAvailable();
        if (smsSender != null) {
            String text = messages.getMessage("team.added.sms", args, locale);
            afterCommit.run(() -> smsSender.send(phone, text));
            return SentBy.SMS;
        }
        EmailSender emailSender = email.getIfAvailable();
        if (emailSender != null && to != null) {
            EmailMessage message = composer.in(locale)
                    .subject("email.team.added.subject", args)
                    .paragraph("email.team.added.intro", args)
                    .action("email.team.added.action", webBaseUrl + "/sign-in")
                    .build();
            afterCommit.run(() -> emailSender.send(to, message));
            return SentBy.EMAIL;
        }
        return null;
    }
}
