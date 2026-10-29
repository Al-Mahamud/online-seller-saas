package com.mojud.identity.twofactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** RFC 6238 appendix B (SHA-1, last six digits of each eight-digit code) and RFC 4648 section 10. */
class TotpTests {

    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void codesMatchTheRfcTestVectors() {
        assertThat(code(59)).isEqualTo("287082");
        assertThat(code(1111111109)).isEqualTo("081804");
        assertThat(code(1111111111)).isEqualTo("050471");
        assertThat(code(1234567890)).isEqualTo("005924");
        assertThat(code(2000000000)).isEqualTo("279037");
        assertThat(code(20000000000L)).isEqualTo("353130");
    }

    @Test
    void base32MatchesTheRfc() {
        assertThat(Totp.base32("".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(Totp.base32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Totp.base32("foob".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YQ");
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.base32(Totp.newSecret())).hasSize(32);
    }

    private static String code(long epochSecond) {
        return Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(epochSecond)));
    }
}
