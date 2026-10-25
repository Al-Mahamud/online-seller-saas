package com.mojud.shared.phone;

import com.fasterxml.jackson.annotation.JsonValue;
import com.mojud.shared.error.ApiException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Bangladesh mobile number, stored in E.164 form ({@code +8801712345678}). Phone numbers are
 * how sellers identify customers and how users sign in, so every way people write the same number
 * must give the same value: {@code 01712-345678}, {@code +880 1712 345678}, {@code 8801712345678}
 * and {@code ০১৭১২৩৪৫৬৭৮} (Bangla digits) are all equal.
 */
public record PhoneNumber(String e164) {

    /** Operators use 013 to 019 after the leading 0. */
    private static final Pattern NATIONAL = Pattern.compile("01[3-9]\\d{8}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()]");

    public PhoneNumber {
        if (e164 == null || !e164.startsWith("+880") || !NATIONAL.matcher(e164.substring(3)).matches()) {
            throw new IllegalArgumentException("Not a Bangladesh mobile number in E.164 form");
        }
    }

    /** @throws IllegalArgumentException when {@code input} is not a Bangladesh mobile number */
    public static PhoneNumber parse(String input) {
        return tryParse(input).orElseThrow(() -> new IllegalArgumentException("Not a Bangladesh mobile number"));
    }

    public static Optional<PhoneNumber> tryParse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String digits = SEPARATORS.matcher(asciiDigits(input.strip())).replaceAll("");
        if (digits.startsWith("+880")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("880")) {
            digits = digits.substring(2);
        }
        return NATIONAL.matcher(digits).matches() ? Optional.of(new PhoneNumber("+88" + digits)) : Optional.empty();
    }

    /**
     * A number typed into a form field.
     *
     * @throws ApiException a validation error on {@code field} when it is missing or not a
     *         Bangladesh mobile number
     */
    public static PhoneNumber fromInput(String input, String field) {
        return tryParse(input).orElseThrow(() -> ApiException.invalidField(field, "field.phone_invalid"));
    }

    /**
     * An optional number typed into a form field: blank means none.
     *
     * @return the number, or null when blank
     * @throws ApiException a validation error on {@code field} when it is not a Bangladesh mobile number
     */
    public static PhoneNumber fromOptionalInput(String input, String field) {
        return input == null || input.isBlank() ? null : fromInput(input, field);
    }

    /** The way people write it in Bangladesh: {@code 01712345678}. */
    public String national() {
        return e164.substring(3);
    }

    @JsonValue
    @Override
    public String e164() {
        return e164;
    }

    @Override
    public String toString() {
        return e164;
    }

    private static String asciiDigits(String input) {
        StringBuilder result = new StringBuilder(input.length());
        input.codePoints().forEach(c -> result.appendCodePoint(c >= '০' && c <= '৯' ? '0' + (c - '০') : c));
        return result.toString();
    }
}
