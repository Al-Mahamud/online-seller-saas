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
     * "28 Oct 2026, 9:58 PM", or in Bangla "২৮ অক্টো ২০২৬, রাত ৯:৫৮": Bangla says the part of the day
     * first and has no AM or PM (as the app shows times).
     */
    static String when(Instant at, Locale locale) {
        if (!"bn".equals(locale.getLanguage())) {
            return DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", locale).withZone(DhakaTime.ZONE).format(at);
        }
        int hour = at.atZone(DhakaTime.ZONE).getHour();
        String part = hour >= 4 && hour < 12 ? "সকাল"
                : hour >= 12 && hour < 16 ? "দুপুর"
                : hour >= 16 && hour < 18 ? "বিকাল"
                : hour >= 18 && hour < 20 ? "সন্ধ্যা"
                : "রাত";
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM yyyy", locale).withDecimalStyle(DecimalStyle.of(locale))
                .withZone(DhakaTime.ZONE);
        DateTimeFormatter time = DateTimeFormatter.ofPattern("h:mm", locale).withDecimalStyle(DecimalStyle.of(locale))
                .withZone(DhakaTime.ZONE);
        return day.format(at) + ", " + part + " " + time.format(at);
    }

    /**
     * {@code kind}: a message prefix such as {@code email.security.two_factor_on}; {@code more}: its
     * arguments after the time (snapshots in SecurityEmailTests).
     */
    static EmailMessage email(EmailComposer.Draft draft, String kind, Instant at, String webBaseUrl, Object... more) {
        Locale locale = draft.locale();
        Object[] args = new Object[more.length + 1];
        args[0] = when(at, locale);
        System.arraycopy(more, 0, args, 1, more.length);
        return draft.subject(kind + ".subject")
                .paragraph(kind + ".body", args)
                .paragraph("email.security.not_you")
                .action("email.security.action", webBaseUrl + "/settings/security")
                .build();
    }
}
