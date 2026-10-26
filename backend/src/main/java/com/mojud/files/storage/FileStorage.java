package com.mojud.files.storage;

import java.util.Optional;

/**
 * Where processed files live. Local disk for now; an S3/R2 implementation arrives with deployment
 * (implementation-plan.md 13b), when there is a bucket to test against.
 */
public interface FileStorage {

    void store(String key, byte[] content);

    Optional<byte[]> load(String key);

    void delete(String key);
}
