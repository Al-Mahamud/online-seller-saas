package com.mojud.shared.paging;

import com.mojud.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * A position in a list sorted by some key and then by id: the last row of the previous page.
 * Lists are paged by "rows after this one" (keyset paging), so a page never repeats or skips rows
 * when new rows arrive, and deep pages stay fast.
 *
 * <p>Clients treat the encoded form as opaque. A cursor that cannot be decoded is a validation
 * error on the {@code cursor} parameter.
 */
public record Cursor(String sortKey, UUID id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public Cursor {
        Objects.requireNonNull(sortKey, "sortKey");
        Objects.requireNonNull(id, "id");
    }

    public String encode() {
        return ENCODER.encodeToString((id + "|" + sortKey).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The sort key of a list sorted by time, such as newest first.
     *
     * @throws ApiException a validation error on {@code cursor} when the key is not a time (tampered)
     */
    public Instant sortKeyAsInstant() {
        try {
            return Instant.parse(sortKey);
        } catch (DateTimeParseException e) {
            throw ApiException.invalidField("cursor", "field.invalid_value");
        }
    }

    /** @return the decoded cursor, or {@code null} for a missing or blank one (the first page) */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String text = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
            int separator = text.indexOf('|');
            return new Cursor(text.substring(separator + 1), UUID.fromString(text.substring(0, separator)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw ApiException.invalidField("cursor", "field.invalid_value");
        }
    }
}
