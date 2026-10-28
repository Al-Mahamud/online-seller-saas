package com.mojud.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Upgrade prices on fixed dates (implementation-plan.md 11b): Starter ৳990 to Pro ৳2,990, poisha. */
class ProrationTest {

    static final long STARTER = 99000;
    static final long PRO = 299000;
    static final LocalDate START = LocalDate.of(2026, 11, 1);
    static final LocalDate END = LocalDate.of(2026, 12, 1);

    @Test
    void onTheFirstDayTheWholeDifference() {
        assertThat(Proration.charge(STARTER, PRO, START, END, START)).isEqualTo(200000);
    }

    @Test
    void midMonthHalf() {
        // 15 of 30 days left.
        assertThat(Proration.charge(STARTER, PRO, START, END, LocalDate.of(2026, 11, 16))).isEqualTo(100000);
    }

    @Test
    void onTheLastDayOneDayRoundedUpToWholeTaka() {
        // ৳2,000 × 1 ÷ 30 = ৳66.67, rounded up to ৳67.
        assertThat(Proration.charge(STARTER, PRO, START, END, LocalDate.of(2026, 11, 30))).isEqualTo(6700);
    }

    @Test
    void nothingAfterTheEndOrForACheaperPlan() {
        assertThat(Proration.charge(STARTER, PRO, START, END, END)).isZero();
        assertThat(Proration.charge(PRO, STARTER, START, END, START)).isZero();
    }

    @Test
    void aYearUsesTheYearlyPrices() {
        LocalDate yearEnd = LocalDate.of(2027, 11, 1);
        // A year costs ten months: ৳9,900 and ৳29,900; 365 days, halfway (182 left on 2 May 2027).
        assertThat(Proration.charge(STARTER * 10, PRO * 10, START, yearEnd, LocalDate.of(2027, 5, 3)))
                .isEqualTo(997300);
    }
}
