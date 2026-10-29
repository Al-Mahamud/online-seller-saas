package com.mojud.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.email.EmailMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Output compared with reviewed files in src/test/resources/snapshots. A missing snapshot is
 * written and the test fails, to be read and committed; {@code -DupdateSnapshots=true} rewrites
 * them all without failing (review the diff).
 */
public final class Snapshots {

    static final Path ROOT = Path.of("src/test/resources/snapshots");

    private Snapshots() {
    }

    public static void matches(String name, String actual) {
        Path file = ROOT.resolve(name);
        try {
            if (Boolean.getBoolean("updateSnapshots")) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                return;
            }
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                throw new AssertionError("Snapshot " + file + " written: read it, then commit it");
            }
            assertThat(actual).as(name).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code email/<name>.txt} (the subject, then the text) and {@code email/<name>.html}. */
    public static void matchesEmail(String name, EmailMessage message) {
        matches("email/" + name + ".txt", "Subject: " + message.subject() + "\n\n" + message.text());
        matches("email/" + name + ".html", message.html());
    }
}
