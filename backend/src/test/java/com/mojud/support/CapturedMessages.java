package com.mojud.support;

import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.email.EmailMessage;
import com.mojud.shared.email.EmailSender;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.sms.SmsSender;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps every SMS and email instead of sending it, so tests (and {@link DevOtpController} in local
 * runs) can read one-time codes.
 */
public final class CapturedMessages implements SmsSender, EmailSender {

    private static final Logger log = LoggerFactory.getLogger(CapturedMessages.class);
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    /** {@code body} is the text (an email's plain-text part); {@code html} is null for SMS. */
    public record Message(String to, String subject, String body, String html) {
    }

    private final List<Message> messages = new CopyOnWriteArrayList<>();

    @Override
    public void send(PhoneNumber to, String text) {
        log.info("SMS to {}: {}", to, text);
        messages.add(new Message(to.e164(), null, text, null));
    }

    @Override
    public void send(EmailAddress to, EmailMessage message) {
        log.info("Email to {}: {}\n{}", to, message.subject(), message.text());
        messages.add(new Message(to.value(), message.subject(), message.text(), message.html()));
    }

    /** Every message to this E.164 phone or lower-case email, oldest first. */
    public List<Message> to(String destination) {
        return messages.stream().filter(message -> message.to().equals(destination)).toList();
    }

    /** The code in the newest message to this destination. */
    public Optional<String> lastCode(String destination) {
        List<Message> received = to(destination);
        if (received.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = CODE.matcher(received.get(received.size() - 1).body());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
