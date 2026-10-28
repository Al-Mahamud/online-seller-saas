package com.mojud.reporting;

import com.mojud.catalog.CatalogData;
import com.mojud.customers.CustomerData;
import com.mojud.orders.OrderData;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's copy of the business's data (implementation-plan.md 12b): a zip of CSVs (orders,
 * products with stock, customers), each written by the module that owns it. A data right, so no
 * plan limit applies, and it stays open to a read-only business.
 */
@RestController
class BusinessExportController {

    private final OrderData orders;
    private final CatalogData catalog;
    private final CustomerData customers;
    private final Clock clock;

    BusinessExportController(OrderData orders, CatalogData catalog, CustomerData customers, Clock clock) {
        this.orders = orders;
        this.catalog = catalog;
        this.customers = customers;
        this.clock = clock;
    }

    @GetMapping("/api/v1/business/export")
    @RequiresRole(BusinessRole.OWNER)
    void export(HttpServletResponse response) {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("mojud-export-" + DhakaTime.today(clock) + ".zip").build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Locale locale = LocaleContextHolder.getLocale();
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {
            entry(zip, "orders.csv", orders::writeOrdersCsv, locale);
            entry(zip, "products.csv", catalog::writeProductsCsv, locale);
            entry(zip, "customers.csv", customers::writeCustomersCsv, locale);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void entry(ZipOutputStream zip, String name, BiConsumer<OutputStream, Locale> writer, Locale locale)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        writer.accept(zip, locale);
        zip.closeEntry();
    }
}
