package com.mojud.files.storage;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where processed files live: local disk ({@code mojud.storage.provider=local}, the default) or
 * any S3 API such as Cloudflare R2 ({@code s3}, servers). Keys are made by the server, never by
 * clients, and start with the business id, so a business's files can be removed together.
 */
public interface FileStorage {

    /** Lower-case segments with dashes, one file extension, no "..", no leading slash. */
    Pattern KEY = Pattern.compile("[a-z0-9-]+(/[a-z0-9-]+)*\\.[a-z0-9]+");

    /** One or more whole segments, ending with "/": a business ({@code <id>/}), say. */
    Pattern PREFIX = Pattern.compile("[a-z0-9-]+(/[a-z0-9-]+)*/");

    void store(String key, byte[] content);

    Optional<byte[]> load(String key);

    void delete(String key);

    /** Every file whose key starts with {@code prefix}. */
    void deleteAll(String prefix);

    static String checkKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return key;
    }

    static String checkPrefix(String prefix) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid storage prefix: " + prefix);
        }
        return prefix;
    }
}
