package com.mojud.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DhakaTimeTest {

    /** 18:30 UTC on 31 October is 00:30 on 1 November in Dhaka (UTC+6). */
    private static final Instant JUST_AFTER_DHAKA_MIDNIGHT = Instant.parse("2026-10-31T18:30:00Z");

    private final Clock clock = Clock.fixed(JUST_AFTER_DHAKA_MIDNIGHT, ZoneOffset.UTC);

    @Test
    void todayIsTheDhakaDateNotTheUtcDate() {
        assertThat(DhakaTime.today(clock)).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void currentMonthIsTheDhakaMonth() {
        assertThat(DhakaTime.currentMonth(clock)).isEqualTo(YearMonth.of(2026, 11));
        assertThat(DhakaTime.monthOf(JUST_AFTER_DHAKA_MIDNIGHT)).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    void dayAndMonthStartAtDhakaMidnight() {
        assertThat(DhakaTime.startOf(LocalDate.of(2026, 11, 1))).isEqualTo(Instant.parse("2026-10-31T18:00:00Z"));
        assertThat(DhakaTime.startOf(YearMonth.of(2026, 11))).isEqualTo(Instant.parse("2026-10-31T18:00:00Z"));
    }
}
