package com.mojud.orders;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Packing slips as pages to print or save as PDF. {@code lang} (bn or en) picks the language, by
 * default the reader's; {@code autoprint=true} opens the print dialog. These answers are pages, so
 * they carry their own Content-Security-Policy: inline styles, embedded images, the one print script.
 */
@RestController
@RequiresRole(BusinessRole.STAFF)
class SlipController {

    private final PackingSlips slips;

    SlipController(PackingSlips slips) {
        this.slips = slips;
    }

    @GetMapping("/api/v1/orders/{id}/slip")
    ResponseEntity<String> slip(@PathVariable UUID id, @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "false") boolean autoprint) {
        return page(slips.render(List.of(id), bangla(lang), autoprint));
    }

    /** Many slips in one document, one page each, in the order given. */
    @GetMapping("/api/v1/orders/print")
    ResponseEntity<String> print(@RequestParam List<UUID> ids, @RequestParam(required = false) String lang,
            @RequestParam(defaultValue = "false") boolean autoprint) {
        return page(slips.render(ids, bangla(lang), autoprint));
    }

    private static boolean bangla(String lang) {
        if (lang != null && !lang.isBlank()) {
            return lang.equalsIgnoreCase("bn");
        }
        return LocaleContextHolder.getLocale().getLanguage().equals("bn");
    }

    private static ResponseEntity<String> page(String html) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", PackingSlips.CONTENT_SECURITY_POLICY)
                .body(html);
    }
}
