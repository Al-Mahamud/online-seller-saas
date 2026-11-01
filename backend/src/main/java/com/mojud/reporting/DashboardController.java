package com.mojud.reporting;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every member sees the dashboard; profit only managers and owners. */
@RestController
@RequiresRole(BusinessRole.STAFF)
class DashboardController {

    private final Dashboard dashboard;

    DashboardController(Dashboard dashboard) {
        this.dashboard = dashboard;
    }

    /** {@code date}: another Dhaka day to look back at (default today). */
    @GetMapping("/api/v1/dashboard/today")
    Dashboard.Today today(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboard.today(date);
    }

    /** What needs attention and how deliveries stand now, whichever day is on screen. */
    @GetMapping("/api/v1/dashboard/overview")
    Dashboard.Overview overview() {
        return dashboard.overview();
    }
}
