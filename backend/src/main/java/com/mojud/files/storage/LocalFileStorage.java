package com.mojud.files.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Files under {@code mojud.storage.local-root}/{key}. Keys are made by the server, never by clients. */
@Component
class LocalFileStorage implements FileStorage {

    /** Lower-case segments with dashes, one file extension, no "..", no leading slash. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9-]+(/[a-z0-9-]+)*\\.[a-z0-9]+");

    private final Path root;

    LocalFileStorage(@Value("${mojud.storage.local-root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void store(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // Write to a temporary file first so a reader never sees half a file.
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store " + key, e);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + key, e);
        }
    }

    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage root: " + key);
        }
        return path;
    }
}
