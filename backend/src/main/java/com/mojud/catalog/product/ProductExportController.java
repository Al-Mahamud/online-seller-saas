package com.mojud.catalog.product;

import com.mojud.billing.Entitlements;
import com.mojud.shared.csv.CsvDownload;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Products with stock as CSV, for managers and owners (it includes cost prices). */
@RestController
@RequiresRole(BusinessRole.MANAGER)
class ProductExportController {

    private final ProductExport export;
    private final Entitlements entitlements;
    private final Clock clock;

    ProductExportController(ProductExport export, Clock clock, Entitlements entitlements) {
        this.entitlements = entitlements;
        this.export = export;
        this.clock = clock;
    }

    @GetMapping("/api/v1/exports/products.csv")
    void products(HttpServletResponse response) {
        entitlements.requireExports();
        export.write(CsvDownload.start(response, "products", clock), LocaleContextHolder.getLocale());
    }
}
