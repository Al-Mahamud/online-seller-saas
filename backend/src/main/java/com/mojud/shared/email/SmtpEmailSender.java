package com.mojud.shared.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends email through any SMTP provider (Brevo, Amazon SES, ...), as plain text with an HTML
 * alternative. Settings under {@code mojud.email.smtp} (environment: SMTP_HOST, SMTP_PORT,
 * SMTP_USERNAME, SMTP_PASSWORD) and {@code mojud.email.from} (EMAIL_FROM); missing ones stop the
 * app from starting rather than losing mail later. STARTTLS is required unless
 * {@code mojud.email.smtp.starttls=false} (only for a server on the same machine).
 *
 * <p>The mail sender is kept private, not a bean, so Spring Boot adds no mail health check:
 * {@code /healthz} must not depend on the provider being reachable.
 */
@Component
@ConditionalOnProperty(name = "mojud.email.provider", havingValue = "smtp")
class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    private static final String TIMEOUT_MS = "10000";

    private final JavaMailSenderImpl mail = new JavaMailSenderImpl();
    private final InternetAddress from;

    SmtpEmailSender(Environment environment) {
        mail.setHost(required(environment, "mojud.email.smtp.host"));
        mail.setPort(environment.getProperty("mojud.email.smtp.port", Integer.class, 587));
        mail.setUsername(environment.getProperty("mojud.email.smtp.username"));
        mail.setPassword(environment.getProperty("mojud.email.smtp.password"));
        mail.setDefaultEncoding(StandardCharsets.UTF_8.name());
        boolean starttls = environment.getProperty("mojud.email.smtp.starttls", Boolean.class, true);
        Properties properties = mail.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(mail.getUsername() != null && !mail.getUsername().isBlank()));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(starttls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(starttls));
        properties.setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS);
        properties.setProperty("mail.smtp.timeout", TIMEOUT_MS);
        properties.setProperty("mail.smtp.writetimeout", TIMEOUT_MS);
        try {
            InternetAddress[] parsed = InternetAddress.parse(required(environment, "mojud.email.from"), true);
            if (parsed.length != 1) {
                throw new IllegalStateException("mojud.email.from must be one address, like: Mojud <no-reply@example.com>");
            }
            from = parsed[0];
        } catch (jakarta.mail.internet.AddressException e) {
            throw new IllegalStateException("mojud.email.from is not an email address", e);
        }
    }

    @Override
    public void send(EmailAddress to, EmailMessage message) {
        try {
            MimeMessage mime = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from.getAddress(), from.getPersonal(), StandardCharsets.UTF_8.name()));
            helper.setTo(to.value());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            mail.send(mime);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // After commit, in the background: the caller has already answered. Logged for alerting (Sentry, 13b).
            log.error("Email to {} failed: {}", to, message.subject(), e);
        }
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required when mojud.email.provider=smtp");
        }
        return value;
    }
}
