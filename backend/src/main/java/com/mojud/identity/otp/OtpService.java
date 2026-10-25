package com.mojud.identity.otp;

import com.mojud.identity.auth.IdentityProperties;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.email.EmailSender;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.id.Ids;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.sms.SmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Six-digit codes sent by SMS or email, in the requester's language. Only a hash is stored; a code
 * expires after {@code mojud.identity.otp.ttl}, allows {@code max-attempts} guesses, and is
 * replaced by any newer code for the same destination and purpose. Rate limits are applied by the
 * caller.
 *
 * <p>A channel is available only when a sender for it is configured: without an SMS gateway,
 * codes go by email only.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository challenges;
    private final ObjectProvider<SmsSender> sms;
    private final ObjectProvider<EmailSender> email;
    private final MessageSource messages;
    private final Clock clock;
    private final IdentityProperties.Otp settings;

    OtpService(OtpChallengeRepository challenges, ObjectProvider<SmsSender> sms, ObjectProvider<EmailSender> email,
            MessageSource messages, Clock clock, IdentityProperties properties) {
        this.challenges = challenges;
        this.sms = sms;
        this.email = email;
        this.messages = messages;
        this.clock = clock;
        this.settings = properties.otp();
    }

    public Set<OtpChannel> availableChannels() {
        Set<OtpChannel> channels = EnumSet.noneOf(OtpChannel.class);
        if (sms.getIfAvailable() != null) {
            channels.add(OtpChannel.PHONE);
        }
        if (email.getIfAvailable() != null) {
            channels.add(OtpChannel.EMAIL);
        }
        return channels;
    }

    /** @throws ApiException {@code otp.channel_unavailable} when no sender serves this channel */
    public void requireAvailable(OtpChannel channel) {
        if (!availableChannels().contains(channel)) {
            throw new ApiException(ErrorCode.OTP_CHANNEL_UNAVAILABLE);
        }
    }

    @Transactional
    public void issue(OtpDestination destination, OtpPurpose purpose) {
        requireAvailable(destination.channel());
        Instant now = clock.instant();
        UUID id = Ids.newId();
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        challenges.save(new OtpChallenge(id, destination, purpose, hash(id, code), now.plus(settings.ttl()), now));
        // Sent inside the transaction on purpose: if sending fails, no unusable challenge is left.
        Locale locale = LocaleContextHolder.getLocale();
        Object[] args = {code, settings.ttl().toMinutes()};
        if (destination.isPhone()) {
            sms.getObject().send(new PhoneNumber(destination.value()), messages.getMessage("otp.sms", args, locale));
        } else {
            email.getObject().send(new EmailAddress(destination.value()),
                    messages.getMessage("otp.email.subject", args, locale),
                    messages.getMessage("otp.email.body", args, locale));
        }
    }

    /**
     * Uses up the newest code for this destination and purpose, or fails with
     * {@code otp.invalid}. Runs in its own transaction so that a failed attempt is counted even
     * though the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void consume(OtpDestination destination, OtpPurpose purpose, String code) {
        Instant now = clock.instant();
        OtpChallenge challenge = challenges
                .findFirstByDestinationAndPurposeOrderByCreatedAtDesc(destination.value(), purpose)
                .filter(c -> c.isUsable(now, settings.maxAttempts()))
                .orElseThrow(OtpService::invalid);
        byte[] expected = challenge.getCodeHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(challenge.getId(), code).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            challenge.recordFailedAttempt();
            throw invalid();
        }
        challenge.consume(now);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.OTP_INVALID);
    }

    private static String hash(UUID challengeId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
