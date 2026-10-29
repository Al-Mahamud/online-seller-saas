package com.mojud.identity.auth;

import com.mojud.identity.account.User;
import com.mojud.shared.async.AfterCommitTasks;
import com.mojud.shared.email.EmailComposer;
import com.mojud.shared.email.EmailMessage;
import com.mojud.shared.email.EmailSender;
import com.mojud.shared.time.DhakaTime;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells a user by email when their sign-in security changes (two-step sign-in on or off, a
 * recovery code used), so someone else doing it does not go unnoticed. Only to a verified email,
 * in the user's language.
 */
@Component
class SecurityEmails {

    private final ObjectProvider<EmailSender> sender;
    private final EmailComposer composer;
    private final AfterCommitTasks afterCommit;
    private final Clock clock;
    private final String webBaseUrl;

    SecurityEmails(ObjectProvider<EmailSender> sender, EmailComposer composer, AfterCommitTasks afterCommit, Clock clock,
            @Value("${mojud.web.base-url}") String webBaseUrl) {
        this.sender = sender;
        this.composer = composer;
        this.afterCommit = afterCommit;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl;
    }

    void twoFactorOn(User user) {
        send(user, "email.security.two_factor_on");
    }

    void twoFactorOff(User user) {
        send(user, "email.security.two_factor_off");
    }

    void recoveryCodeUsed(User user, int left) {
        send(user, "email.security.recovery_code_used", left);
    }

    private void send(User user, String kind, Object... more) {
        EmailSender email = sender.getIfAvailable();
        if (email == null || !user.isEmailVerified() || user.getEmail().isEmpty()) {
            return;
        }
        EmailMessage message = email(composer.in(Locale.forLanguageTag(user.getLocale())), kind, clock.instant(),
                webBaseUrl, more);
        afterCommit.run(() -> email.send(user.getEmail().get(), message));
    }

    /**
     * {@code kind}: a message prefix such as {@code email.security.two_factor_on}; {@code more}: its
     * arguments after the time (snapshots in SecurityEmailTests).
     */
    static EmailMessage email(EmailComposer.Draft draft, String kind, Instant at, String webBaseUrl, Object... more) {
        Locale locale = draft.locale();
        Object[] args = new Object[more.length + 1];
        args[0] = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", locale).withDecimalStyle(DecimalStyle.of(locale))
                .withZone(DhakaTime.ZONE).format(at);
        System.arraycopy(more, 0, args, 1, more.length);
        return draft.subject(kind + ".subject")
                .paragraph(kind + ".body", args)
                .paragraph("email.security.not_you")
                .action("email.security.action", webBaseUrl + "/settings/security")
                .build();
    }
}
