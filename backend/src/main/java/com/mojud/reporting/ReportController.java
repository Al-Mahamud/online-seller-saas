package com.mojud.reporting;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import com.mojud.shared.error.ApiException;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales (every member) and profit (managers and owners), over Dhaka days {@code from} to
 * {@code to}, both included: by default the last 30 days; at most 366.
 */
@RestController
@RequiresRole(BusinessRole.STAFF)
class ReportController {

    private final SalesReports reports;

    ReportController(SalesReports reports) {
        this.reports = reports;
    }

    /** {@code groupBy}: day (default), product or category, in any case. */
    @GetMapping("/api/v1/reports/sales")
    SalesReports.Sales sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String groupBy) {
        SalesReports.GroupBy group;
        try {
            group = SalesReports.GroupBy.valueOf(groupBy.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("groupBy", "field.invalid_value");
        }
        return reports.sales(from, to, group);
    }

    @GetMapping("/api/v1/reports/profit")
    @RequiresRole(BusinessRole.MANAGER)
    SalesReports.Profit profit(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.profit(from, to);
    }
}
