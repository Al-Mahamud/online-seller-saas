package com.mojud.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Both languages must have every message, so no user ever sees a raw key. */
class MessagesTest {

    @Test
    void englishAndBanglaHaveTheSameKeys() throws IOException {
        assertThat(load("messages_bn.properties").stringPropertyNames())
                .containsExactlyInAnyOrderElementsOf(load("messages.properties").stringPropertyNames());
    }

    @Test
    void everyErrorCodeHasAMessage() throws IOException {
        Properties english = load("messages.properties");

        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::messageKey))
                .allSatisfy(key -> assertThat(english).containsKey(key));
    }

    @Test
    void errorCodesAreUnique() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code)).doesNotHaveDuplicates();
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                MessagesTest.class.getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
