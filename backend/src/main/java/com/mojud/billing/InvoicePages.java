package com.mojud.billing;

import com.mojud.shared.money.Taka;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessProfile;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Mojud's invoices to a business as printable HTML (implementation-plan.md 11b; browsers draw Bangla
 * correctly, so PDFs are saved from the browser). In Bangla or English; VAT shown.
 */
@Component
class InvoicePages {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'; "
            + "base-uri 'none'; form-action 'none'";

    private final MessageSource messages;
    private final Environment environment;
    private final Plans plans;

    InvoicePages(MessageSource messages, Environment environment, Plans plans) {
        this.messages = messages;
        this.environment = environment;
        this.plans = plans;
    }

    String render(Invoices.InvoiceView invoice, BusinessProfile.Profile business, boolean bangla) {
        Locale locale = Locale.forLanguageTag(bangla ? "bn-BD" : "en-BD");
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM yyyy", locale).withDecimalStyle(DecimalStyle.of(locale))
                .withZone(DhakaTime.ZONE);
        Plans.Plan plan = plans.get(invoice.planCode());
        String planName = bangla ? plan.nameBn() : plan.nameEn();
        String what = invoice.kind().equals("UPGRADE")
                ? label("invoice.upgradeTo", locale, planName)
                : label("invoice.planFor", locale, planName, invoice.months());
        String period = invoice.periodStart() == null ? ""
                : day.format(invoice.periodStart()) + " – " + (invoice.periodEnd() == null ? "" : day.format(invoice.periodEnd()));
        StringBuilder html = new StringBuilder(4096);
        html.append("<!doctype html>\n<html lang=\"").append(bangla ? "bn" : "en").append("\">\n<head>\n<meta charset=\"utf-8\">\n<title>")
                .append(e(label("invoice.title", locale) + " " + invoice.number())).append("</title>\n<style>\n").append(STYLE)
                .append("</style>\n</head>\n<body>\n<main class=\"invoice\">\n<header>\n<div><h1>")
                .append(e(environment.getProperty("mojud.billing.seller.name", "Mojud"))).append("</h1><p>")
                .append(e(environment.getProperty("mojud.billing.seller.address", ""))).append("</p>");
        String bin = environment.getProperty("mojud.billing.seller.bin", "");
        if (!bin.isBlank()) {
            html.append("<p>BIN: ").append(e(bin)).append("</p>");
        }
        html.append("</div>\n<div class=\"meta\"><h2>").append(e(label("invoice.title", locale))).append("</h2><p><strong>")
                .append(e(invoice.number())).append("</strong></p><p>").append(e(day.format(invoice.createdAt())))
                .append("</p><p class=\"status ").append(invoice.status().toLowerCase(Locale.ROOT)).append("\">")
                .append(e(label("invoice.status." + invoice.status(), locale))).append("</p></div>\n</header>\n")
                .append("<section class=\"to\"><h3>").append(e(label("invoice.billTo", locale))).append("</h3><p class=\"name\">")
                .append(e(business.name())).append("</p>");
        if (business.phone() != null) {
            html.append("<p>").append(e(digits(new PhoneNumber(business.phone()).display(), bangla))).append("</p>");
        }
        if (business.address() != null && !business.address().isBlank()) {
            html.append("<p>").append(e(business.address())).append("</p>");
        }
        html.append("</section>\n<table>\n<thead><tr><th>").append(e(label("invoice.item", locale))).append("</th><th class=\"n\">")
                .append(e(label("invoice.amount", locale))).append("</th></tr></thead>\n<tbody>\n<tr><td>").append(e(what));
        if (!period.isEmpty()) {
            html.append("<br><span class=\"period\">").append(e(period)).append("</span>");
        }
        long base = invoice.amount() - invoice.vatAmount();
        html.append("</td><td class=\"n\">").append(e(money(base, bangla))).append("</td></tr>\n</tbody>\n<tfoot>\n<tr><th>")
                .append(e(label("invoice.vat", locale))).append("</th><td class=\"n\">").append(e(money(invoice.vatAmount(), bangla)))
                .append("</td></tr>\n<tr class=\"total\"><th>").append(e(label("invoice.total", locale))).append("</th><td class=\"n\">")
                .append(e(money(invoice.amount(), bangla))).append("</td></tr>\n</tfoot>\n</table>\n");
        if (invoice.paidAt() != null) {
            html.append("<p class=\"paid\">").append(e(label("invoice.paidOn", locale, day.format(invoice.paidAt()))))
                    .append("</p>\n");
        }
        html.append("</main>\n</body>\n</html>\n");
        return html.toString();
    }

    private String label(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, key, locale);
    }

    private static String money(long poisha, boolean bangla) {
        return "৳" + digits(Taka.lakh(poisha), bangla);
    }

    private static String digits(String text, boolean bangla) {
        return bangla ? Taka.banglaDigits(text) : text;
    }

    private static String e(String text) {
        return HtmlUtils.htmlEscape(text == null ? "" : text, StandardCharsets.UTF_8.name());
    }

    private static final String STYLE = """
            @page { size: A4; margin: 16mm; }
            body { margin: 0; color: #17201B; font: 10.5pt/1.5 "Hind Siliguri", "Noto Sans Bengali", "Nirmala UI", system-ui, sans-serif; }
            header { display: flex; justify-content: space-between; gap: 8mm; border-bottom: 1.5pt solid #17201B; padding-bottom: 4mm; }
            h1 { margin: 0; font-size: 18pt; color: #0B6B4F; }
            h2 { margin: 0; font-size: 14pt; }
            h3 { margin: 0 0 1mm; font-size: 9pt; text-transform: uppercase; letter-spacing: 0.04em; color: #3A4440; }
            p { margin: 0; }
            .meta { text-align: right; }
            .status { display: inline-block; margin-top: 1mm; padding: 0.5mm 3mm; border-radius: 3mm; font-weight: 700; }
            .status.paid { background: #E6F2EC; color: #08513C; }
            .status.open { background: #FDF1E7; color: #8A4B0B; }
            .to { margin: 6mm 0; }
            .to .name { font-weight: 700; font-size: 12pt; }
            table { width: 100%; border-collapse: collapse; }
            th, td { text-align: left; padding: 2mm; border-bottom: 0.5pt solid #C9CEC9; }
            thead th { font-size: 9pt; color: #3A4440; border-bottom: 1pt solid #17201B; }
            .n { text-align: right; white-space: nowrap; }
            tfoot th { text-align: right; font-weight: 400; }
            .total th, .total td { font-weight: 700; font-size: 12pt; border-top: 1pt solid #17201B; }
            .period { font-size: 9pt; color: #5B655F; }
            .paid { margin-top: 6mm; color: #08513C; font-weight: 600; }
            @media screen { body { background: #E9ECE8; padding: 8mm 0; }
              .invoice { background: #FFFFFF; width: 178mm; margin: 0 auto; padding: 14mm; box-shadow: 0 1mm 4mm rgba(0,0,0,0.15); } }
            """;
}
