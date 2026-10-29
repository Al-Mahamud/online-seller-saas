package com.mojud.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An authenticator app, for tests: the code for a base32 secret (RFC 6238, as the app would show
 * it), written apart from the server's own code so the two check each other.
 */
public final class TestTotp {

    private TestTotp() {
    }

    /** The code {@code stepsAhead} 30-second steps after now on {@code clock} (the server's clock in tests). */
    public static String code(String base32Secret, Clock clock, int stepsAhead) {
        long step = clock.instant().getEpochSecond() / 30 + stepsAhead;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(base32Secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[19] & 0xf;
            int value = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
            return String.format("%06d", value % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
