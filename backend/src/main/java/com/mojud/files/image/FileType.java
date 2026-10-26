package com.mojud.files.image;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Image types recognised by their first bytes, never by file name or the client's Content-Type. */
enum FileType {

    JPEG,
    PNG,
    WEBP;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    static Optional<FileType> detect(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (content.length >= 8 && Arrays.equals(content, 0, 8, PNG_SIGNATURE, 0, 8)) {
            return Optional.of(PNG);
        }
        if (content.length >= 12 && ascii(content, 0, 4).equals("RIFF") && ascii(content, 8, 4).equals("WEBP")) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    static String ascii(byte[] content, int offset, int length) {
        return new String(content, offset, length, StandardCharsets.US_ASCII);
    }
}
