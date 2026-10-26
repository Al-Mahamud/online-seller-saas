package com.mojud.shared.files;

import java.util.UUID;

/**
 * Where the API serves an uploaded image ({@code files} module). Modules that show images, such as
 * product photos or the business logo, build their links here without depending on that module.
 */
public final class FileLinks {

    public record Links(String large, String small) {
    }

    private FileLinks() {
    }

    /** @return the links, or null for no file */
    public static Links of(UUID fileId) {
        return fileId == null ? null
                : new Links("/api/v1/files/" + fileId + "/large", "/api/v1/files/" + fileId + "/small");
    }
}
