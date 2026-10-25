package com.mojud.shared.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"Rahima@Example.COM", " rahima@example.com "})
    void isTrimmedAndLowerCased(String input) {
        assertThat(EmailAddress.tryParse(input)).contains(new EmailAddress("rahima@example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "rahima", "rahima@", "@example.com", "rahima@example", "a b@example.com", "a@b@c.com"})
    void refusesWhatCannotBeAnAddress(String input) {
        assertThat(EmailAddress.tryParse(input)).isEmpty();
    }
}
