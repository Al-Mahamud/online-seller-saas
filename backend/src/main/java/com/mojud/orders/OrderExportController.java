package com.mojud.orders;

import com.mojud.shared.csv.CsvDownload;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Orders as CSV, for managers and owners; every order made from {@code from} to {@code to} (Dhaka days, default all). */
@RestController
@RequiresRole(BusinessRole.MANAGER)
class OrderExportController {

    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    private final OrderExport export;
    private final Clock clock;

    OrderExportController(OrderExport export, Clock clock) {
        this.export = export;
        this.clock = clock;
    }

    @GetMapping("/api/v1/exports/orders.csv")
    void orders(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) {
        LocalDate start = from == null ? EARLIEST : from;
        LocalDate end = to == null ? DhakaTime.today(clock) : to;
        if (start.isAfter(end)) {
            throw ApiException.invalidField("from", "field.report_range");
        }
        export.write(CsvDownload.start(response, "orders", clock), start, end, LocaleContextHolder.getLocale());
    }
}
