package com.mojud.files;

import java.util.Optional;
import java.util.UUID;

/** Reading the business's stored images from other modules (a logo on a packing slip). */
public interface StoredFiles {

    record Content(byte[] bytes, String contentType) {
    }

    /** The small size of an uploaded image of the business being worked in; empty when missing. */
    Optional<Content> small(UUID fileId);
}
