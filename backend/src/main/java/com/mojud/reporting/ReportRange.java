package com.mojud.reporting;

import com.mojud.billing.Entitlements;
import com.mojud.billing.Metric;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.time.DhakaTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * The Dhaka days a report covers, {@code from} to {@code to}, both included: by default the last 30
 * days to today; at most 366, none in the future, and none before the plan's {@code report_days}.
 */
@Component
class ReportRange {

    static final int MAX_DAYS = 366;
    static final int DEFAULT_DAYS = 30;

    record Range(LocalDate from, LocalDate to) {

        int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }

        /** The first moment of {@code from}. */
        Instant start() {
            return DhakaTime.startOf(from);
        }

        /** The first moment after {@code to}. */
        Instant end() {
            return DhakaTime.startOf(to.plusDays(1));
        }

        /** As many days, just before these. */
        Range before() {
            return new Range(from.minusDays(days()), from.minusDays(1));
        }
    }

    private final Entitlements entitlements;
    private final Clock clock;

    ReportRange(Entitlements entitlements, Clock clock) {
        this.entitlements = entitlements;
        this.clock = clock;
    }

    /** @throws ApiException {@code field.report_range} on {@code from}, or the plan's limit */
    Range resolve(LocalDate from, LocalDate to) {
        LocalDate today = DhakaTime.today(clock);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;
        if (start.isAfter(end) || end.isAfter(today)) {
            throw ApiException.invalidField("from", "field.report_range");
        }
        Range range = new Range(start, end);
        if (range.days() > MAX_DAYS) {
            throw ApiException.invalidField("from", "field.report_range");
        }
        requireReachable(start);
        return range;
    }

    /** The plan's report_days: how far back reports may look (Starter: 90 days, including today). */
    void requireReachable(LocalDate day) {
        entitlements.limit(Metric.REPORT_DAYS).ifPresent(days -> {
            if (day.isBefore(DhakaTime.today(clock).minusDays(days - 1))) {
                throw new ApiException(ErrorCode.PLAN_LIMIT_REACHED).withDetail("metric", Metric.REPORT_DAYS.key())
                        .withDetail("limit", days);
            }
        });
    }

    /** Whether the plan reaches back to {@code day}, without refusing. */
    boolean reaches(LocalDate day) {
        return entitlements.limit(Metric.REPORT_DAYS)
                .map(days -> !day.isBefore(DhakaTime.today(clock).minusDays(days - 1)))
                .orElse(true);
    }
}
