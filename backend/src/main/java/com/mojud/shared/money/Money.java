package com.mojud.shared.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount in Bangladeshi taka, held as whole poisha (1 taka = 100 poisha) so sums are exact
 * (backend-plan.md §5). In JSON it is a plain integer of poisha: {@code 120050} is ৳1,200.50.
 * Amounts may be negative (refunds, adjustments); overflow throws instead of wrapping.
 */
public record Money(long poisha) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    @JsonCreator
    public static Money ofPoisha(long poisha) {
        return new Money(poisha);
    }

    public static Money ofTaka(long taka) {
        return new Money(Math.multiplyExact(taka, 100L));
    }

    /** @throws ArithmeticException when the amount has more than two decimal places */
    public static Money ofTaka(BigDecimal taka) {
        return new Money(taka.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact());
    }

    @JsonValue
    @Override
    public long poisha() {
        return poisha;
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(poisha, other.poisha));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(poisha, other.poisha));
    }

    public Money times(long quantity) {
        return new Money(Math.multiplyExact(poisha, quantity));
    }

    public Money negate() {
        return new Money(Math.negateExact(poisha));
    }

    public boolean isNegative() {
        return poisha < 0;
    }

    public boolean isZero() {
        return poisha == 0;
    }

    public BigDecimal toTaka() {
        return BigDecimal.valueOf(poisha, 2);
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(poisha, other.poisha);
    }

    /** For logs and tests, for example {@code 1200.50}. Display formatting belongs to the frontend. */
    @Override
    public String toString() {
        return toTaka().toPlainString();
    }
}
