package com.mojud.shared.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets kept in the database (backend-plan.md §11): AES-256-GCM with a key that is not
 * in the database, {@code mojud.security.encryption-key} (ENCRYPTION_KEY, 32 random bytes in
 * base64: {@code openssl rand -base64 32}). Each value is sealed with what it belongs to (such as
 * the user id), so a sealed value copied to another row does not open. Without a key nothing can
 * be sealed, and features that need it say they are unavailable.
 */
@Component
public class SecretBox {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    SecretBox(@Value("${mojud.security.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            key = null;
            return;
        }
        byte[] bytes = Base64.getDecoder().decode(base64Key.strip());
        if (bytes.length != 32) {
            throw new IllegalStateException("mojud.security.encryption-key must be 32 bytes in base64");
        }
        key = new SecretKeySpec(bytes, "AES");
    }

    public boolean available() {
        return key != null;
    }

    /** The nonce, then the ciphertext with its tag. */
    public byte[] seal(byte[] plain, byte[] belongsTo) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] sealed = crypt(Cipher.ENCRYPT_MODE, nonce, belongsTo, plain);
        return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
    }

    /** @throws IllegalStateException when the value was changed, or sealed with another key or owner */
    public byte[] open(byte[] sealed, byte[] belongsTo) {
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(sealed, 0, NONCE_BYTES), belongsTo,
                Arrays.copyOfRange(sealed, NONCE_BYTES, sealed.length));
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] belongsTo, byte[] input) {
        if (key == null) {
            throw new IllegalStateException("No encryption key (mojud.security.encryption-key)");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(belongsTo);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not " + (mode == Cipher.ENCRYPT_MODE ? "seal" : "open") + " the value", e);
        }
    }
}
