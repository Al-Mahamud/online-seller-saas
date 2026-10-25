package com.mojud.shared.text;

import com.mojud.shared.error.ApiException;

/** Checks on text people type. */
public final class Text {

    private Text() {
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
