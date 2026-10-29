package com.mojud.shared.email;

/**
 * Sends an email: by SMTP where {@code mojud.email.provider=smtp} ({@link SmtpEmailSender}), to the
 * log where it is {@code log} (local runs and tests). With neither, nothing can be emailed.
 */
public interface EmailSender {

    void send(EmailAddress to, EmailMessage message);
}
