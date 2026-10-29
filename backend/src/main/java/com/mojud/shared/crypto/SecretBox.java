package com.mojud.shared.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets kept in the database (backend-plan.md §11): AES-256-GCM with a key that is not
 * in the database, {@code mojud.security.encryption-key} (ENCRYPTION_KEY, 32 random bytes in
 * base64: {@code openssl rand -base64 32}). Each value is sealed with what it belongs to (such as
 * the user id), so a sealed value copied to another row does not open. Without a key nothing can
 * be sealed, and features that need it say they are unavailable.
 *
 * <p>Rotating the key (docs/operations.md): the new key becomes ENCRYPTION_KEY and the old one
 * goes to ENCRYPTION_KEY_PREVIOUS ({@code mojud.security.previous-encryption-keys}, comma
 * separated). Values sealed with an old key still open; each owner re-seals its values with the
 * new key ({@link #sealedWithCurrentKey}), after which the old key can be removed.
 */
@Component
public class SecretBox {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final List<SecretKey> previous;

    @Autowired
    public SecretBox(@Value("${mojud.security.encryption-key:}") String base64Key,
            @Value("${mojud.security.previous-encryption-keys:}") String previousBase64Keys) {
        this.key = base64Key == null || base64Key.isBlank() ? null : key(base64Key, "mojud.security.encryption-key");
        this.previous = new ArrayList<>();
        if (previousBase64Keys != null) {
            for (String old : previousBase64Keys.split(",")) {
                if (!old.isBlank()) {
                    previous.add(key(old, "mojud.security.previous-encryption-keys"));
                }
            }
        }
    }

    SecretBox(String base64Key) {
        this(base64Key, "");
    }

    public boolean available() {
        return key != null;
    }

    /** True while old keys are configured: their values should be re-sealed with the current one. */
    public boolean hasPreviousKeys() {
        return !previous.isEmpty();
    }

    /** The nonce, then the ciphertext with its tag, under the current key. */
    public byte[] seal(byte[] plain, byte[] belongsTo) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] sealed = crypt(current(), Cipher.ENCRYPT_MODE, nonce, belongsTo, plain);
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    }

    /**
     * Opens with the current key, or else an old one.
     *
     * @throws IllegalStateException when the value was changed, or sealed with another key or owner
     */
    public byte[] open(byte[] sealed, byte[] belongsTo) {
        try {
            return open(current(), sealed, belongsTo);
        } catch (IllegalStateException e) {
            for (SecretKey old : previous) {
                try {
                    return open(old, sealed, belongsTo);
                } catch (IllegalStateException ignored) {
                    // Try the next old key.
                }
            }
            throw e;
        }
    }

    /** True when the current key opens it: nothing to re-seal. */
    public boolean sealedWithCurrentKey(byte[] sealed, byte[] belongsTo) {
        try {
            open(current(), sealed, belongsTo);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private byte[] open(SecretKey with, byte[] sealed, byte[] belongsTo) {
        return crypt(with, Cipher.DECRYPT_MODE, Arrays.copyOfRange(sealed, 0, NONCE_BYTES), belongsTo,
                Arrays.copyOfRange(sealed, NONCE_BYTES, sealed.length));
    }

    private SecretKey current() {
        if (key == null) {
            throw new IllegalStateException("No encryption key (mojud.security.encryption-key)");
        }
        return key;
    }

    private static byte[] crypt(SecretKey with, int mode, byte[] nonce, byte[] belongsTo, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, with, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(belongsTo);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not " + (mode == Cipher.ENCRYPT_MODE ? "seal" : "open") + " the value", e);
        }
    }

    private static SecretKey key(String base64, String setting) {
        byte[] bytes = Base64.getDecoder().decode(base64.strip());
        if (bytes.length != 32) {
            throw new IllegalStateException(setting + " must be 32 bytes in base64");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
