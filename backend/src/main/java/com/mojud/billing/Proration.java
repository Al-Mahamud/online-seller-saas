package com.mojud.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What an upgrade costs (backend-plan.md §9): (new price − old price) × days left ÷ days in the
 * period, for the period as paid (its months, a year at the yearly price), rounded up to whole taka.
 * Days are Dhaka calendar days; the day of the upgrade counts as left.
 */
final class Proration {

    private Proration() {
    }

    /** Poisha, before VAT; 0 when the period has no day left. */
    static long charge(long oldPeriodPrice, long newPeriodPrice, LocalDate periodStart, LocalDate periodEnd, LocalDate today) {
        long daysInPeriod = ChronoUnit.DAYS.between(periodStart, periodEnd);
        long daysLeft = ChronoUnit.DAYS.between(today, periodEnd);
        if (daysLeft <= 0 || daysInPeriod <= 0 || newPeriodPrice <= oldPeriodPrice) {
            return 0;
        }
        BigDecimal exact = BigDecimal.valueOf(newPeriodPrice - oldPeriodPrice)
                .multiply(BigDecimal.valueOf(Math.min(daysLeft, daysInPeriod)))
                .divide(BigDecimal.valueOf(daysInPeriod), 10, RoundingMode.HALF_UP);
        // Up to whole taka (100 poisha).
        return exact.divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact() * 100;
    }
}
