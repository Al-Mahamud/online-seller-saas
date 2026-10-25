package com.mojud.shared.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "01712345678", "01712-345678", "017 1234 5678", "+8801712345678", "+880 1712-345678",
            "8801712345678", "(+880) 1712.345678", "০১৭১২৩৪৫৬৭৮", "+৮৮০১৭১২৩৪৫৬৭৮", " 01712345678 "})
    void everyCommonWayOfWritingANumberGivesTheSameValue(String input) {
        PhoneNumber phone = PhoneNumber.parse(input);

        assertThat(phone.e164()).isEqualTo("+8801712345678");
        assertThat(phone.national()).isEqualTo("01712345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "0171234567", "017123456789", "01212345678", "02-9123456", "+447911123456",
            "1712345678", "01712345abc", "+88017123456789"})
    void anythingElseIsRefused(String input) {
        assertThat(PhoneNumber.tryParse(input)).isEmpty();
        assertThatThrownBy(() -> PhoneNumber.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"01712345678", "+880171234567"})
    void theConstructorOnlyAcceptsE164(String value) {
        assertThatThrownBy(() -> new PhoneNumber(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
