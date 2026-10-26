package com.mojud.tenancy.staff;

import com.mojud.identity.UserDirectory;
import com.mojud.shared.async.AfterCommitTasks;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.email.EmailSender;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.ratelimit.RateLimiter;
import com.mojud.shared.sms.SmsSender;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.business.BusinessNames;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import com.mojud.tenancy.system.InviteDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inviting people to a business and accepting invites (implementation-plan.md 2b).
 *
 * <p>The invite link carries a random token; only a hash is stored. Accepting needs a signed-in
 * account that has verified the invited phone or email, so a forwarded or leaked link cannot be
 * used by anyone else.
 */
@Service
class InviteService {

    record Preview(String businessName, BusinessRole role, String invitedBy, String sentTo, Instant expiresAt) {
    }

    record Joined(UUID businessId, String businessName, BusinessRole role) {
    }

    private final InviteRepository invites;
    private final MembershipRepository memberships;
    private final InviteDirectory directory;
    private final BusinessNames businessNames;
    private final UserDirectory users;
    private final ObjectProvider<SmsSender> sms;
    private final ObjectProvider<EmailSender> email;
    private final AfterCommitTasks afterCommit;
    private final MessageSource messages;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;
    private final AdvisoryLocks locks;
    private final Clock clock;
    private final Duration ttl;
    private final String webBaseUrl;
    private final RateLimiter invitesPerBusiness;

    InviteService(InviteRepository invites, MembershipRepository memberships, InviteDirectory directory,
            BusinessNames businessNames, UserDirectory users, ObjectProvider<SmsSender> sms,
            ObjectProvider<EmailSender> email, AfterCommitTasks afterCommit, MessageSource messages,
            PlatformTransactionManager transactionManager, AdvisoryLocks locks, Clock clock,
            @Value("${mojud.tenancy.invites.ttl}") Duration ttl,
            @Value("${mojud.tenancy.invites.per-business.max}") int perBusinessMax,
            @Value("${mojud.tenancy.invites.per-business.window}") Duration perBusinessWindow,
            @Value("${mojud.web.base-url}") String webBaseUrl) {
        this.invites = invites;
        this.memberships = memberships;
        this.directory = directory;
        this.businessNames = businessNames;
        this.users = users;
        this.sms = sms;
        this.email = email;
        this.afterCommit = afterCommit;
        this.messages = messages;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.locks = locks;
        this.clock = clock;
        this.ttl = ttl;
        this.webBaseUrl = webBaseUrl;
        this.invitesPerBusiness = new RateLimiter("invites-per-business", perBusinessMax, perBusinessWindow);
    }

    /**
     * Invites a phone or email as MANAGER or STAFF, replacing any open invite to the same address.
     * The message goes out after the invite is saved, in the inviter's language.
     */
    @Transactional
    InviteResponse invite(String to, BusinessRole role) {
        CurrentBusiness business = CurrentBusiness.require();
        if (role == BusinessRole.OWNER) {
            throw ApiException.invalidField("role", "field.invalid_value");
        }
        boolean isEmail = to.contains("@");
        String destination = isEmail
                ? EmailAddress.tryParse(to).orElseThrow(() -> ApiException.invalidField("to", "field.login_invalid")).value()
                : PhoneNumber.fromInput(to, "to").e164();
        if (isEmail ? email.getIfAvailable() == null : sms.getIfAvailable() == null) {
            throw new ApiException(ErrorCode.INVITE_CHANNEL_UNAVAILABLE);
        }
        // Invites to one address take turns, so two at once cannot both find no open invite.
        locks.lockForTransaction("invite:" + business.businessId() + ":" + destination);
        Optional<UUID> existingUser = users.findByVerifiedContact(destination);
        if (existingUser.flatMap(memberships::findByUserId).filter(Membership::isActive).isPresent()) {
            throw new ApiException(ErrorCode.STAFF_ALREADY_MEMBER);
        }
        // Counted only for an invite that will really be sent.
        invitesPerBusiness.acquire(business.businessId().toString());

        Instant now = clock.instant();
        invites.findUnanswered(destination).ifPresent(old -> {
            old.revoke(now);
            // The new invite must not meet the old one in the "one open invite" index.
            invites.saveAndFlush(old);
        });
        String token = InviteTokens.newToken();
        Invite invite = invites.save(new Invite(isEmail ? InviteChannel.EMAIL : InviteChannel.PHONE, destination,
                role, InviteTokens.hash(token), business.userId(), now.plus(ttl), now));
        send(invite, token, business);
        return InviteResponse.of(invite);
    }

    @Transactional(readOnly = true)
    List<InviteResponse> open() {
        return invites.findOpen(clock.instant()).stream().map(InviteResponse::of).toList();
    }

    @Transactional
    void revoke(UUID inviteId) {
        Invite invite = invites.findById(inviteId).filter(found -> found.isOpen(clock.instant()))
                .orElseThrow(ApiException::notFound);
        invite.revoke(clock.instant());
    }

    /** What the invite link shows before anyone signs in. Reads only: takes no lock. */
    Preview preview(String token) {
        String hash = InviteTokens.hash(token);
        UUID businessId = businessOf(hash);
        return TenantScope.call(businessId, () -> readOnly.execute(status -> {
            Invite invite = usable(invites.findByTokenHash(hash), businessId, clock.instant());
            return new Preview(businessNames.nameOf(businessId), invite.getRole(),
                    users.name(invite.getInvitedBy()).orElse(""), InviteTokens.mask(invite.getDestination()),
                    invite.getExpiresAt());
        }));
    }

    /**
     * Makes the signed-in user a member with the invited role (or brings a former member back).
     * Works in the invite's business, whatever business the request has selected.
     */
    Joined accept(UUID userId, String token) {
        String hash = InviteTokens.hash(token);
        UUID businessId = businessOf(hash);
        return TenantScope.call(businessId, () -> transactions.execute(status -> {
            Instant now = clock.instant();
            Invite invite = usable(invites.findByTokenHashForUpdate(hash), businessId, now);
            if (!users.hasVerified(userId, invite.getDestination())) {
                throw new ApiException(ErrorCode.INVITE_WRONG_ACCOUNT, InviteTokens.mask(invite.getDestination()));
            }
            // One person's joins take turns (two invites, by phone and by email, accepted at once).
            locks.lockForTransaction("membership:" + businessId + ":" + userId);
            Optional<Membership> existing = memberships.findByUserId(userId);
            if (existing.filter(Membership::isActive).isPresent()) {
                throw new ApiException(ErrorCode.STAFF_ALREADY_MEMBER);
            }
            existing.ifPresentOrElse(
                    former -> former.rejoin(invite.getRole(), now),
                    () -> memberships.save(new Membership(userId, invite.getRole(), now)));
            invite.accept(userId, now);
            return new Joined(businessId, businessNames.nameOf(businessId), invite.getRole());
        }));
    }

    private UUID businessOf(String tokenHash) {
        return directory.businessOfToken(tokenHash).orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID));
    }

    /**
     * The one definition of a usable invite, for preview and accept alike: open, in an active
     * business, and sent by someone who is still a manager or owner there (an invite does not
     * outlive its sender's right to invite).
     */
    private Invite usable(Optional<Invite> found, UUID businessId, Instant now) {
        return found
                .filter(invite -> invite.isOpen(now))
                .filter(invite -> businessNames.isActive(businessId))
                .filter(invite -> memberships.findActive(invite.getInvitedBy())
                        .filter(sender -> sender.getRole().atLeast(BusinessRole.MANAGER)).isPresent())
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_INVALID));
    }

    private void send(Invite invite, String token, CurrentBusiness business) {
        Locale locale = LocaleContextHolder.getLocale();
        String link = webBaseUrl + "/invites#" + token;
        Object[] args = {
            users.name(business.userId()).orElse(""),
            businessNames.nameOf(business.businessId()),
            messages.getMessage("role." + invite.getRole(), null, locale),
            link,
            ttl.toDays()
        };
        if (invite.getChannel() == InviteChannel.PHONE) {
            SmsSender sender = sms.getObject();
            PhoneNumber to = new PhoneNumber(invite.getDestination());
            String text = messages.getMessage("invite.sms", args, locale);
            afterCommit.run(() -> sender.send(to, text));
        } else {
            EmailSender sender = email.getObject();
            EmailAddress to = new EmailAddress(invite.getDestination());
            String subject = messages.getMessage("invite.email.subject", args, locale);
            String body = messages.getMessage("invite.email.body", args, locale);
            afterCommit.run(() -> sender.send(to, subject, body));
        }
    }
}
