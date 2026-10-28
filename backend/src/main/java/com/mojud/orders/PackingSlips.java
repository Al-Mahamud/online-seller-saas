package com.mojud.orders;

import com.mojud.files.StoredFiles;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.money.Taka;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * Packing slips and invoices as print-ready HTML (implementation-plan.md 8d): one A5 page per
 * order, in Bangla or English, with the business's logo (embedded, so a saved PDF keeps it), the
 * delivery details, the items, the money, and the cash to collect in large type for the courier.
 */
@Component
class PackingSlips {

    static final int MAX_ORDERS = 100;

    /** Opens the print dialog when asked to ({@code autoprint}); allowed by its hash, nothing else runs. */
    static final String PRINT_SCRIPT = "window.addEventListener('load',function(){window.print()})";
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; "
            + "script-src '" + sha256(PRINT_SCRIPT) + "'; frame-ancestors 'self'; base-uri 'none'; form-action 'none'";

    private static final Locale BANGLA = Locale.forLanguageTag("bn-BD");
    private static final Locale ENGLISH = Locale.forLanguageTag("en-BD");

    private final OrderRepository orders;
    private final OrderService service;
    private final BusinessProfile profiles;
    private final StoredFiles files;
    private final MessageSource messages;

    PackingSlips(OrderRepository orders, OrderService service, BusinessProfile profiles, StoredFiles files,
            MessageSource messages) {
        this.orders = orders;
        this.service = service;
        this.profiles = profiles;
        this.files = files;
        this.messages = messages;
    }

    /** {@code bangla}: the page's language. Every id must be an order of this business. */
    @Transactional(readOnly = true)
    String render(List<UUID> orderIds, boolean bangla, boolean autoprint) {
        if (orderIds.isEmpty() || orderIds.size() > MAX_ORDERS) {
            throw ApiException.invalidField("ids", "field.bulk_count");
        }
        List<OrderViews.Detail> details = orderIds.stream()
                .map(id -> service.detail(orders.findById(id).orElseThrow(ApiException::notFound))).toList();
        BusinessProfile.Profile business = profiles.current();
        String logo = business.logoFileId() == null ? null : files.small(business.logoFileId())
                .map(content -> "data:" + content.contentType() + ";base64," + Base64.getEncoder().encodeToString(content.bytes()))
                .orElse(null);
        Page page = new Page(bangla ? BANGLA : ENGLISH, bangla);
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html>\n<html lang=\"").append(bangla ? "bn" : "en").append("\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>").append(e(details.size() == 1 ? page.label("slip.order") + " " + details.get(0).numberLabel()
                        : page.label("slip.title"))).append("</title>\n<style>\n").append(STYLE).append("</style>\n");
        if (autoprint) {
            html.append("<script>").append(PRINT_SCRIPT).append("</script>\n");
        }
        html.append("</head>\n<body>\n");
        for (OrderViews.Detail order : details) {
            slip(html, order, business, logo, page);
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private void slip(StringBuilder html, OrderViews.Detail order, BusinessProfile.Profile business, String logo, Page page) {
        html.append("<section class=\"slip\">\n<header>\n");
        if (logo != null) {
            html.append("<img class=\"logo\" src=\"").append(logo).append("\" alt=\"\">\n");
        }
        html.append("<div class=\"business\"><h1>").append(e(business.name())).append("</h1>");
        String contact = String.join(" · ", Stream.of(
                business.phone() == null ? null : page.phone(business.phone()), business.address())
                .filter(s -> s != null && !s.isBlank()).toList());
        if (!contact.isEmpty()) {
            html.append("<p>").append(e(contact)).append("</p>");
        }
        html.append("</div>\n<div class=\"number\"><span>").append(e(page.label("slip.order"))).append("</span><strong>")
                .append(e(page.digits(order.numberLabel()))).append("</strong><span>")
                .append(e(page.date(order.confirmedAt() != null ? order.confirmedAt() : order.createdAt()))).append("</span></div>\n")
                .append("</header>\n");

        html.append("<div class=\"cod\">");
        if (order.codAmount() > 0) {
            html.append("<span>").append(e(page.label("slip.cod"))).append("</span><strong>").append(e(page.money(order.codAmount())))
                    .append("</strong>");
        } else {
            html.append("<span>").append(e(page.label("slip.codNone"))).append("</span><strong>").append(e(page.money(0)))
                    .append("</strong>");
        }
        html.append("</div>\n");

        OrderViews.Delivery delivery = order.delivery();
        html.append("<div class=\"parties\">\n<div><h2>").append(e(page.label("slip.deliverTo"))).append("</h2><p class=\"name\">")
                .append(e(delivery.name())).append("</p><p>").append(e(page.phone(delivery.phone()))).append("</p>");
        if (!delivery.address().isBlank()) {
            html.append("<p>").append(e(delivery.address())).append("</p>");
        }
        String place = String.join(", ", Stream.of(delivery.area(), delivery.district())
                .filter(Objects::nonNull).map(g -> page.bangla() ? g.nameBn() : g.nameEn()).toList());
        if (!place.isEmpty()) {
            html.append("<p>").append(e(place)).append("</p>");
        }
        html.append("</div>\n");
        OrderViews.ShipmentView shipment = order.shipments().isEmpty() ? null : order.shipments().get(order.shipments().size() - 1);
        if (shipment != null) {
            html.append("<div><h2>").append(e(page.label("slip.courier"))).append("</h2><p class=\"name\">").append(e(shipment.courier()))
                    .append("</p>");
            if (!shipment.trackingNo().isBlank()) {
                html.append("<p>").append(e(page.label("slip.tracking"))).append(": ").append(e(shipment.trackingNo())).append("</p>");
            }
            html.append("</div>\n");
        }
        html.append("</div>\n");

        html.append("<table class=\"items\">\n<thead><tr><th>").append(e(page.label("slip.item"))).append("</th><th class=\"n\">")
                .append(e(page.label("slip.qty"))).append("</th><th class=\"n\">").append(e(page.label("slip.price")))
                .append("</th><th class=\"n\">").append(e(page.label("slip.amount"))).append("</th></tr></thead>\n<tbody>\n");
        for (OrderViews.Item item : order.items()) {
            html.append("<tr><td>").append(e(item.productName()));
            if (!item.variantLabel().isEmpty()) {
                html.append(" <span class=\"label\">").append(e(item.variantLabel())).append("</span>");
            }
            html.append("<br><span class=\"sku\">").append(e(item.sku())).append("</span></td><td class=\"n\">")
                    .append(e(page.digits(Integer.toString(item.quantity())))).append("</td><td class=\"n\">")
                    .append(e(page.money(item.unitPrice()))).append("</td><td class=\"n\">").append(e(page.money(item.amount())))
                    .append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n<table class=\"totals\">\n");
        total(html, page, "slip.subtotal", order.subtotal(), false);
        if (order.discount() > 0) {
            total(html, page, "slip.discount", -order.discount(), false);
        }
        total(html, page, "slip.delivery", order.deliveryCharge(), false);
        total(html, page, "slip.total", order.total(), true);
        if (order.advancePaid() > 0) {
            total(html, page, "slip.advance", -order.advancePaid(), false);
        }
        total(html, page, "slip.cod", order.codAmount(), true);
        html.append("</table>\n");
        if (!order.note().isBlank()) {
            html.append("<p class=\"note\"><b>").append(e(page.label("slip.note"))).append(":</b> ").append(e(order.note()))
                    .append("</p>\n");
        }
        if (!business.invoiceFooter().isBlank()) {
            html.append("<footer>").append(e(business.invoiceFooter())).append("</footer>\n");
        }
        html.append("</section>\n");
    }

    private static void total(StringBuilder html, Page page, String key, long poisha, boolean strong) {
        html.append(strong ? "<tr class=\"strong\">" : "<tr>").append("<th>").append(e(page.label(key))).append("</th><td>")
                .append(e(poisha < 0 ? "− " + page.money(-poisha) : page.money(poisha))).append("</td></tr>\n");
    }

    /** Labels, digits, money and dates in the page's language. */
    private final class Page {

        private final Locale locale;
        private final boolean bangla;
        private final DateTimeFormatter dates;

        Page(Locale locale, boolean bangla) {
            this.locale = locale;
            this.bangla = bangla;
            this.dates = DateTimeFormatter.ofPattern("d MMM yyyy", locale).withDecimalStyle(DecimalStyle.of(locale))
                    .withZone(DhakaTime.ZONE);
        }

        boolean bangla() {
            return bangla;
        }

        String label(String key) {
            return messages.getMessage(key, null, key, locale);
        }

        String date(Instant at) {
            return dates.format(at);
        }

        /** "01712-345678", the way it is written in Bangladesh. */
        String phone(String e164) {
            String national = new PhoneNumber(e164).national();
            return digits(national.substring(0, 5) + "-" + national.substring(5));
        }

        String money(long poisha) {
            return "৳" + digits(Taka.lakh(poisha));
        }

        String digits(String text) {
            return bangla ? Taka.banglaDigits(text) : text;
        }
    }

    private static String e(String text) {
        return HtmlUtils.htmlEscape(text == null ? "" : text, StandardCharsets.UTF_8.name());
    }

    private static String sha256(String script) {
        try {
            return "sha256-" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String STYLE = """
            @page { size: A5; margin: 9mm; }
            * { box-sizing: border-box; }
            body { margin: 0; color: #17201B; font: 10.5pt/1.45 "Hind Siliguri", "Noto Sans Bengali", "Nirmala UI", Vrinda, system-ui, sans-serif; }
            .slip { break-after: page; page-break-after: always; }
            .slip:last-child { break-after: auto; page-break-after: auto; }
            header { display: flex; align-items: center; gap: 4mm; border-bottom: 1.5pt solid #17201B; padding-bottom: 3mm; }
            .logo { height: 14mm; width: auto; max-width: 30mm; object-fit: contain; }
            .business { flex: 1; min-width: 0; }
            h1 { margin: 0; font-size: 14pt; line-height: 1.2; }
            header p { margin: 0.5mm 0 0; color: #3A4440; font-size: 9pt; }
            .number { text-align: right; display: flex; flex-direction: column; }
            .number span { font-size: 8.5pt; color: #3A4440; }
            .number strong { font-size: 15pt; line-height: 1.15; }
            .cod { margin: 4mm 0; border: 2pt solid #17201B; border-radius: 2.5mm; padding: 2.5mm 4mm; display: flex;
                   justify-content: space-between; align-items: center; gap: 4mm; }
            .cod span { font-size: 10pt; font-weight: 600; }
            .cod strong { font-size: 24pt; line-height: 1.1; font-variant-numeric: tabular-nums; }
            .parties { display: flex; gap: 6mm; margin-bottom: 3mm; }
            .parties > div { flex: 1; min-width: 0; }
            h2 { margin: 0 0 1mm; font-size: 8.5pt; text-transform: uppercase; letter-spacing: 0.04em; color: #3A4440; }
            .parties p { margin: 0; }
            .parties .name { font-weight: 700; font-size: 11.5pt; }
            table { width: 100%; border-collapse: collapse; }
            .items th { text-align: left; font-size: 8.5pt; color: #3A4440; border-bottom: 1pt solid #17201B; padding: 1mm 1.5mm; }
            .items td { padding: 1.5mm; border-bottom: 0.5pt solid #C9CEC9; vertical-align: top; }
            .n { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
            .items th.n { text-align: right; }
            .label { color: #3A4440; }
            .sku { font-size: 8pt; color: #5B655F; }
            .totals { width: 60%; margin: 2mm 0 0 auto; }
            .totals th { text-align: left; font-weight: 400; padding: 0.6mm 1.5mm; }
            .totals td { text-align: right; padding: 0.6mm 1.5mm; font-variant-numeric: tabular-nums; white-space: nowrap; }
            .totals .strong th, .totals .strong td { font-weight: 700; border-top: 0.75pt solid #17201B; }
            .note { margin: 3mm 0 0; padding: 2mm; background: #F1F3F0; border-radius: 1.5mm; }
            footer { margin-top: 4mm; padding-top: 2mm; border-top: 0.5pt solid #C9CEC9; font-size: 8.5pt; color: #3A4440;
                     text-align: center; white-space: pre-line; }
            @media screen {
              body { background: #E9ECE8; padding: 8mm 0; }
              .slip { background: #FFFFFF; width: 148mm; margin: 0 auto 8mm; padding: 9mm; box-shadow: 0 1mm 4mm rgba(0,0,0,0.15); }
            }
            """;
}
