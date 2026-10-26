package com.mojud.shared.text;

import com.mojud.shared.error.ApiException;
import java.text.Normalizer;

/** Checks on text people type. */
public final class Text {

    private Text() {
    }

    /**
     * A short name people type, such as a category or tag: trimmed, runs of white space made one
     * space, and Unicode-normalised (NFC), so the same Bangla word typed two ways is one name.
     *
     * @throws ApiException a validation error on {@code field} when blank or longer than {@code maxLength}
     */
    public static String cleanName(String value, String field, int maxLength) {
        String cleaned = Normalizer.normalize(requireNonBlank(value, field), Normalizer.Form.NFC)
                .replaceAll("\\s+", " ");
        if (cleaned.length() > maxLength) {
            throw ApiException.invalidField(field, "field.too_long");
        }
        return cleaned;
    }

    /**
     * Trims {@code value} with Java's full notion of white space, which also covers spaces such as
     * U+2003 that Bean Validation's {@code @NotBlank} lets through.
     *
     * @throws ApiException a "must not be blank" validation error on {@code field} when nothing is left
     */
    public static String requireNonBlank(String value, String field) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.isEmpty()) {
            throw ApiException.invalidField(field, "jakarta.validation.constraints.NotBlank.message");
        }
        return cleaned;
    }
}
