package com.mojud.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MoneyTest {

    @Test
    void takaAndPoishaConvertExactly() {
        assertThat(Money.ofTaka(1200).poisha()).isEqualTo(120_000);
        assertThat(Money.ofTaka(new BigDecimal("1200.50")).poisha()).isEqualTo(120_050);
        assertThat(Money.ofPoisha(120_050).toTaka()).isEqualByComparingTo("1200.50");
        assertThat(Money.ofPoisha(120_050)).hasToString("1200.50");
    }

    @Test
    void moreThanTwoDecimalPlacesIsRefused() {
        assertThatThrownBy(() -> Money.ofTaka(new BigDecimal("10.005"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void arithmetic() {
        Money price = Money.ofTaka(450);

        assertThat(price.times(3).plus(Money.ofTaka(70)).minus(Money.ofTaka(50))).isEqualTo(Money.ofTaka(1370));
        assertThat(price.negate().isNegative()).isTrue();
        assertThat(price.minus(price).isZero()).isTrue();
        assertThat(Money.ofTaka(1)).isLessThan(Money.ofTaka(2));
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        assertThatThrownBy(() -> Money.ofPoisha(Long.MAX_VALUE).plus(Money.ofPoisha(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void jsonIsAPlainNumberOfPoisha() {
        JsonMapper json = JsonMapper.builder().build();
        record Price(Money amount) {
        }

        assertThat(json.writeValueAsString(new Price(Money.ofPoisha(120_050)))).isEqualTo("{\"amount\":120050}");
        assertThat(json.readValue("{\"amount\":120050}", Price.class).amount()).isEqualTo(Money.ofPoisha(120_050));
    }
}
