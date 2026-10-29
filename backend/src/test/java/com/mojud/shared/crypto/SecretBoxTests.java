package com.mojud.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTests {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void aSealedValueOpensOnlyForWhatItBelongsTo() {
        SecretBox box = new SecretBox(KEY);
        byte[] plain = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = box.seal(plain, bytes("user:1"));

        assertThat(box.open(sealed, bytes("user:1"))).isEqualTo(plain);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("secret");
        assertThat(box.seal(plain, bytes("user:1"))).as("a fresh nonce each time").isNotEqualTo(sealed);
        assertThatThrownBy(() -> box.open(sealed, bytes("user:2"))).isInstanceOf(IllegalStateException.class);
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> box.open(sealed, bytes("user:1"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyCannotOpenIt() {
        byte[] sealed = new SecretBox(KEY).seal(bytes("x"), bytes("a"));
        byte[] other = new byte[32];
        other[0] = 1;
        SecretBox box = new SecretBox(Base64.getEncoder().encodeToString(other));
        assertThatThrownBy(() -> box.open(sealed, bytes("a"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutAKeyNothingIsSealed() {
        SecretBox box = new SecretBox("");
        assertThat(box.available()).isFalse();
        assertThatThrownBy(() -> box.seal(bytes("x"), bytes("a"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32 bytes");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
