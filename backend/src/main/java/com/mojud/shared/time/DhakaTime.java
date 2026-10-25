package com.mojud.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Calendar questions answered in Bangladesh time. Timestamps are stored in UTC, but "today's
 * sales" and "orders this month" mean the seller's day and month: an order placed at 00:30 on
 * 1 November in Dhaka is still 31 October in UTC.
 */
public final class DhakaTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

    private DhakaTime() {
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public static YearMonth currentMonth(Clock clock) {
        return YearMonth.now(clock.withZone(ZONE));
    }

    public static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(ZONE));
    }

    /** Midnight in Dhaka at the start of {@code day}. */
    public static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    /** Midnight in Dhaka on the first day of {@code month}. */
    public static Instant startOf(YearMonth month) {
        return startOf(month.atDay(1));
    }
}
