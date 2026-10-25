package com.mojud.shared.email;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An email address, trimmed and lower-cased so the same address always gives the same value. The
 * check is deliberately loose: whether an address works is proved by sending it a code.
 */
public record EmailAddress(String value) {

    private static final Pattern SHAPE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final int MAX_LENGTH = 254;

    public EmailAddress {
        if (value == null || value.length() > MAX_LENGTH || !SHAPE.matcher(value).matches()
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Not a normalised email address");
        }
    }

    public static Optional<EmailAddress> tryParse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalised = input.strip().toLowerCase(Locale.ROOT);
        return normalised.length() <= MAX_LENGTH && SHAPE.matcher(normalised).matches()
                ? Optional.of(new EmailAddress(normalised))
                : Optional.empty();
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
