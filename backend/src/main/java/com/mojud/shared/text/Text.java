package com.mojud.shared.text;

import com.mojud.shared.error.ApiException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Checks on text people type. */
public final class Text {

    /**
     * Any white space: ASCII, plus every Unicode space separator, including the no-break space
     * (U+00A0) and em space (U+2003) that text pasted from phones and web pages often carries.
     */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern EDGE_SPACES = Pattern.compile("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$");

    private Text() {
    }

    /**
     * A short name people type, such as a category or tag: Unicode-normalised (NFC), so the same
     * Bangla word typed two ways is one name; every run of white space made one ASCII space; and
     * trimmed.
     *
     * @throws ApiException a validation error on {@code field} when blank or longer than {@code maxLength}
     */
    public static String cleanName(String value, String field, int maxLength) {
        String normalised = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC);
        String cleaned = requireNonBlank(SPACES.matcher(normalised).replaceAll(" "), field);
        if (cleaned.length() > maxLength) {
            throw ApiException.invalidField(field, "field.too_long");
        }
        return cleaned;
    }

    /**
     * Text as search compares it: lower case and NFC, like the database's {@code search_text}, so
     * য় typed as one character or two finds a name saved either way.
     */
    public static String searchable(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /**
     * A LIKE pattern for text containing {@code value}, as {@link #searchable} has it, with LIKE's
     * own characters (backslash, %, _) escaped.
     */
    public static String containsPattern(String value) {
        return "%" + searchable(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * Trims every kind of white space from both ends, including Unicode spaces such as U+2003 and
     * U+00A0 that Bean Validation's {@code @NotBlank} lets through.
     *
     * @throws ApiException a "must not be blank" validation error on {@code field} when nothing is left
     */
    public static String requireNonBlank(String value, String field) {
        String cleaned = value == null ? "" : EDGE_SPACES.matcher(value).replaceAll("");
        if (cleaned.isEmpty()) {
            throw ApiException.invalidField(field, "jakarta.validation.constraints.NotBlank.message");
        }
        return cleaned;
    }
}
