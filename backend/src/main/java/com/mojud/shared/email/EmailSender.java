package com.mojud.shared.email;

/**
 * Sends an email. Phase 13a adds the SMTP sender; until then only the logging sender exists, where
 * {@code mojud.email.provider=log}.
 */
public interface EmailSender {

    void send(EmailAddress to, String subject, String body);
}
