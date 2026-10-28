package com.mojud.customers;

import com.mojud.shared.csv.CsvDownload;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customers as CSV, for managers and owners. */
@RestController
@RequiresRole(BusinessRole.MANAGER)
class CustomerExportController {

    private final CustomerExport export;
    private final Clock clock;

    CustomerExportController(CustomerExport export, Clock clock) {
        this.export = export;
        this.clock = clock;
    }

    @GetMapping("/api/v1/exports/customers.csv")
    void customers(HttpServletResponse response) {
        export.write(CsvDownload.start(response, "customers", clock), LocaleContextHolder.getLocale());
    }
}
