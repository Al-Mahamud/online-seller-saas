package com.mojud.billing.system;

import com.mojud.audit.AdminAudit;
import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.csv.CsvDownload;
import com.mojud.shared.csv.CsvWriter;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.shared.time.DhakaTime;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billing for the platform's administrators (only PLATFORM_ADMIN reaches /api/v1/admin/**): the
 * invoices across businesses, marking a bank transfer paid (through the same steps as a gateway
 * payment), and the month's ledger for the accountant. Across businesses, so the system role.
 */
@RestController
class BillingAdminController {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(DhakaTime.ZONE);

    record AdminInvoice(UUID id, String number, UUID businessId, String businessName, String kind, String planCode, long amount, String status,
            String payBy, Instant createdAt) {
    }

    record MarkPaidRequest(@NotBlank @Size(max = 100) String reference) {
    }

    private final SystemDatabase database;
    private final PaymentConfirmations confirmations;
    private final AdminAudit audit;
    private final Clock clock;

    BillingAdminController(SystemDatabase database, PaymentConfirmations confirmations, AdminAudit audit, Clock clock) {
        this.database = database;
        this.confirmations = confirmations;
        this.audit = audit;
        this.clock = clock;
    }

    /** Newest first; {@code status} OPEN, PAID, VOID or REFUNDED and {@code payBy} narrow them (default: all). */
    @GetMapping("/api/v1/admin/billing/invoices")
    PagedResult<AdminInvoice> invoices(@RequestParam(required = false) String status,
            @RequestParam(required = false) String payBy, @ParameterObject PageQuery paging) {
        String wantedStatus = status == null ? "" : status;
        String wantedPayBy = payBy == null ? "" : payBy;
        String where = "where (? = '' or i.status = ?) and (? = '' or i.pay_by = ?) ";
        List<AdminInvoice> rows = database.jdbc().sql("""
                        select i.id, i.number, i.business_id, b.name, i.kind, i.plan_code, i.amount, i.status, i.pay_by, i.created_at
                        from invoices i join businesses b on b.id = i.business_id
                        """ + where + "order by i.created_at desc, i.id desc limit ? offset ?")
                .params(wantedStatus, wantedStatus, wantedPayBy, wantedPayBy, paging.limit(), paging.offset())
                .query((rs, n) -> new AdminInvoice(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9),
                        rs.getTimestamp(10).toInstant()))
                .list();
        long total = database.jdbc().sql("select count(*) from invoices i " + where)
                .params(wantedStatus, wantedStatus, wantedPayBy, wantedPayBy).query(Long.class).single();
        return PagedResult.of(rows, paging, total);
    }

    /** A bank transfer seen in the account; {@code reference}: the bank's. 409 if the invoice is not open. */
    @PostMapping("/api/v1/admin/billing/invoices/{id}/mark-paid")
    void markPaid(@PathVariable UUID id, @Valid @RequestBody MarkPaidRequest body,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        UUID business = database.jdbc().sql("select business_id from invoices where id = ?").param(id)
                .query(UUID.class).optional().orElseThrow(ApiException::notFound);
        confirmations.markBankTransfer(business, id, body.reference().strip(), admin.id());
        audit.record(admin.id(), "invoice.marked_paid", "invoice", id.toString(), business, "reference", body.reference().strip());
    }

    /** One Dhaka month of the ledger (default this one), every business, for the accountant. Money in taka. */
    @GetMapping("/api/v1/admin/billing/ledger.csv")
    void ledger(@RequestParam(required = false) String month, HttpServletResponse response) {
        YearMonth period;
        try {
            period = month == null ? DhakaTime.currentMonth(clock) : YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw ApiException.invalidField("month", "field.invalid_value");
        }
        try (CsvWriter csv = new CsvWriter(CsvDownload.start(response, "ledger-" + period, clock))) {
            csv.row(List.of("Date", "Invoice", "Business", "Type", "Method", "Reference", "Amount", "VAT"));
            database.jdbc().sql("""
                            select l.created_at, i.number, l.business_id, l.type, l.method, l.reference, l.amount, l.vat_amount
                            from billing_ledger l join invoices i on i.id = l.invoice_id
                            where l.created_at >= ? and l.created_at < ?
                            order by l.created_at, l.id
                            """)
                    .params(Timestamp.from(DhakaTime.startOf(period)), Timestamp.from(DhakaTime.startOf(period.plusMonths(1))))
                    .query((rs, n) -> {
                        csv.text(WHEN.format(rs.getTimestamp(1).toInstant())).text(rs.getString(2))
                                .text(rs.getObject(3, UUID.class).toString()).text(rs.getString(4)).text(rs.getString(5))
                                .text(rs.getString(6)).taka(rs.getLong(7)).taka(rs.getLong(8)).end();
                        return null;
                    })
                    .list();
        }
    }
}
