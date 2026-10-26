package com.mojud.importing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValuesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1200|120000", "৳1,200|120000", "Tk. 1200/-|120000", "১২০০|120000", "1,20,000.50|12000050",
            "1200.5|120050", "BDT 99|9900", "১,২০০ টাকা|120000", "0|0", "1200.|120000"})
    void pricesAsSellersWriteThem(String text, long poisha) {
        assertThat(Values.poisha(text)).hasValue(poisha);
    }

    @ParameterizedTest
    @ValueSource(strings = {"call for price", "12.345", "-5", "1.2.3", "", "৳", "1e5"})
    void notPrices(String text) {
        assertThat(Values.poisha(text)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"12|12", "১২|12", "12.0|12", "1,200|1200", "007|7", "0|0"})
    void wholeNumbers(String text, int value) {
        assertThat(Values.count(text)).hasValue(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "-1", "ten", "2.5"})
    void notWholeNumbers(String text) {
        assertThat(Values.count(text)).isEmpty();
    }

    @Test
    void hugeNumbersDoNotOverflow() {
        assertThat(Values.count("99999999999999")).hasValue(Integer.MAX_VALUE);
    }
}
