package com.mojud.shared.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Writes emails to the log instead of sending them. Local runs and tests only: codes appear in the log. */
@Component
@ConditionalOnProperty(name = "mojud.email.provider", havingValue = "log")
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailAddress to, String subject, String body) {
        log.info("Email to {}: {} / {}", to, subject, body);
    }
}
