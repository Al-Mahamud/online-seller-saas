package com.mojud.reporting;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import java.time.LocalDate;
import java.util.Locale;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Damaged and lost goods for every member, over Dhaka days {@code from} to {@code to}, both
 * included: by default the last 30 days; at most 366. Money only for managers and owners.
 */
@RestController
@RequiresRole(BusinessRole.STAFF)
class DamageController {

    private final DamageReports damages;

    DamageController(DamageReports damages) {
        this.damages = damages;
    }

    /** {@code source}: shelf or return, in any case; both when left out. {@code q}: a product name or SKU. */
    @GetMapping("/api/v1/damages")
    PagedResult<DamageReports.Damage> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String source, @RequestParam(required = false) String q,
            @ParameterObject PageQuery paging) {
        return damages.list(from, to, source(source), q, paging);
    }

    @GetMapping("/api/v1/damages/summary")
    DamageReports.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) {
        return damages.summary(from, to, q);
    }

    private static DamageReports.Source source(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return DamageReports.Source.valueOf(source.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("source", "field.invalid_value");
        }
    }
}
