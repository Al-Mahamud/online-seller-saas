package com.mojud.shared.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TakaTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|0", "7000|70", "99999|999.99", "100000|1,000", "257050|2,570.50", "10000000|1,00,000",
            "123456750|12,34,567.50", "100000000000|1,00,00,00,000", "5|0.05"})
    void lakhGrouping(long poisha, String taka) {
        assertThat(Taka.lakh(poisha)).isEqualTo(taka);
    }
}
