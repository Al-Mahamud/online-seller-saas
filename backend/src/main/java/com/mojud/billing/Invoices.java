package com.mojud.billing;

import com.mojud.billing.gateway.SslCommerzClient;
import com.mojud.billing.system.PaymentConfirmations;
import com.mojud.identity.UserDirectory;
import com.mojud.notifications.Notifier;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessProfile;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paying for a plan (implementation-plan.md 11a, 11b; backend-plan.md §9). An invoice is opened
 * (a plan for some months, or an upgrade for the rest of the period), then paid through the gateway
 * or by bank transfer. The browser's return proves nothing: only the gateway's validated answer, or
 * an admin who has seen the transfer arrive, marks an invoice paid, through one path
 * ({@link #markPaid}): once, with the subscription's change and a ledger row, in one transaction.
 */
@Service
class Invoices implements PaymentConfirmations {

    static final Set<Integer> MONTHS = Set.of(1, 6, 12);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(DhakaTime.ZONE);

    enum PayBy {
        SSLCOMMERZ, BANK_TRANSFER
    }

    /** Money in poisha. */
    record InvoiceView(UUID id, String number, String kind, String planCode, int months, long amount, long vatAmount,
            String status, String payBy, Instant periodStart, Instant periodEnd, Instant createdAt, Instant paidAt) {
    }

    /** {@code gatewayUrl} for the gateway; {@code bankTransfer} (how to pay) for a bank transfer. */
    record Checkout(UUID invoiceId, String number, long amount, String gatewayUrl, String bankTransfer) {
    }

    /** Published when an invoice is paid, inside that transaction (for receipts). */
    record InvoicePaid(UUID businessId, UUID invoiceId, String kind, String planCode, long amount, Instant periodEnd) {
    }

    private record Attempt(UUID id, UUID invoiceId, long amount) {
    }

    private record Invoice(UUID id, String number, String kind, String planCode, int months, long amount, long vatAmount,
            String status, String payBy) {
    }

    private final Plans plans;
    private final Subscriptions subscriptions;
    private final SslCommerzClient gateway;
    private final UserDirectory users;
    private final BusinessProfile profiles;
    private final Notifier notifier;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final TransactionTemplate fresh;
    private final Clock clock;
    private final BigDecimal vatRate;
    private final Environment environment;

    Invoices(Plans plans, Subscriptions subscriptions, SslCommerzClient gateway, UserDirectory users,
            BusinessProfile profiles, Notifier notifier, AuditTrail audit, ApplicationEventPublisher events,
            DataSource dataSource, JsonMapper json, PlatformTransactionManager transactionManager, Clock clock,
            @Value("${mojud.billing.vat-rate:0}") BigDecimal vatRate, Environment environment) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.gateway = gateway;
        this.users = users;
        this.profiles = profiles;
        this.notifier = notifier;
        this.audit = audit;
        this.events = events;
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.fresh = new TransactionTemplate(transactionManager);
        this.fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.vatRate = vatRate;
        this.environment = environment;
    }

    /** A plan for {@code months}: the monthly price times months, a year for the price of ten (placeholder). */
    static long periodPrice(Plans.Plan plan, int months) {
        return plan.priceMonthly() * (months == 12 ? 10 : months);
    }

    /** [amount with VAT, the VAT in it] for a price before VAT. */
    long[] withVat(long base) {
        long vat = new BigDecimal(base).multiply(vatRate).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new long[] {base + vat, vat};
    }

    /** Pays for {@code months} of a plan: a renewal, a first plan after the trial, or coming back from read-only. */
    Checkout checkout(String planCode, int months, PayBy payBy) {
        if (!MONTHS.contains(months)) {
            throw ApiException.invalidField("months", "field.invalid_value");
        }
        Plans.Plan plan = publicPlan(planCode);
        Subscriptions.Subscription subscription = subscriptions.current();
        if (subscription.status().equals("ACTIVE") && !plan.code().equals(subscription.planCode())) {
            // While a paid period runs, payments extend that plan; another plan is an upgrade, or a
            // downgrade waiting for the renewal.
            throw new ApiException(ErrorCode.BILLING_USE_UPGRADE);
        }
        return open("SUBSCRIPTION", plan, months, withVat(periodPrice(plan, months)), payBy);
    }

    /**
     * A dearer plan for the rest of a running period: (new − old price) × days left ÷ days in the
     * period (backend-plan.md §9). The plan changes when this invoice is paid; the period end stays.
     */
    Checkout upgrade(String planCode, PayBy payBy) {
        Plans.Plan target = publicPlan(planCode);
        Subscriptions.Subscription subscription = subscriptions.current();
        Plans.Plan current = plans.get(subscription.planCode());
        if (!subscription.status().equals("ACTIVE") || subscription.currentPeriodStart() == null
                || subscription.currentPeriodEnd() == null) {
            throw new ApiException(ErrorCode.BILLING_PAYMENT_NEEDED);
        }
        if (target.priceMonthly() <= current.priceMonthly()) {
            throw ApiException.invalidField("planCode", "field.not_an_upgrade");
        }
        LocalDate start = day(subscription.currentPeriodStart());
        LocalDate end = day(subscription.currentPeriodEnd());
        int months = (int) Period.between(start, end).toTotalMonths();
        int paidMonths = MONTHS.contains(months) ? months : 1;
        long charge = Proration.charge(periodPrice(current, paidMonths), periodPrice(target, paidMonths), start, end,
                DhakaTime.today(clock));
        if (charge <= 0) {
            throw new ApiException(ErrorCode.BILLING_USE_UPGRADE);
        }
        return open("UPGRADE", target, 0, withVat(charge), payBy);
    }

    /**
     * Opens the invoice (committed first, so any later answer finds it), then for the gateway asks
     * for its page; a refusal marks the attempt failed.
     */
    private Checkout open(String kind, Plans.Plan plan, int months, long[] price, PayBy payBy) {
        CurrentBusiness me = CurrentBusiness.require();
        if (payBy == PayBy.SSLCOMMERZ && !gateway.configured()) {
            throw new ApiException(ErrorCode.BILLING_GATEWAY_UNAVAILABLE);
        }
        UUID invoiceId = Ids.newId();
        String tranId = payBy == PayBy.SSLCOMMERZ ? "MJ" + random(18) : null;
        String number = transactions.execute(status -> {
            Instant now = clock.instant();
            String invoiceNumber = "MJ-" + String.format("%06d", jdbc.sql("select nextval('invoice_numbers')").query(Long.class).single());
            jdbc.sql("""
                            insert into invoices (id, business_id, number, kind, plan_code, months, amount, vat_amount, status,
                                                  pay_by, created_by, created_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                            """)
                    .params(invoiceId, me.businessId(), invoiceNumber, kind, plan.code(), months, price[0], price[1],
                            payBy.name(), me.userId(), Timestamp.from(now))
                    .update();
            if (tranId != null) {
                jdbc.sql("""
                                insert into payment_attempts (id, business_id, invoice_id, gateway, tran_id, amount, status, created_at)
                                values (?, ?, ?, 'SSLCOMMERZ', ?, ?, 'PENDING', ?)
                                """)
                        .params(Ids.newId(), me.businessId(), invoiceId, tranId, price[0], Timestamp.from(now))
                        .update();
            }
            audit.record(me.businessId(), me.userId(), "invoice.opened", "invoice", invoiceId, "number", invoiceNumber,
                    "kind", kind, "plan", plan.code(), "months", months, "amount", price[0], "payBy", payBy.name());
            return invoiceNumber;
        });
        if (payBy == PayBy.BANK_TRANSFER) {
            return new Checkout(invoiceId, number, price[0], null, environment.getProperty(
                    "mojud.billing.bank-transfer.instructions", "") + " Reference: " + number);
        }
        SslCommerzClient.Session session;
        try {
            session = gateway.session(sessionFields(kind, plan, months, price[0], tranId, invoiceId, me));
        } catch (RuntimeException e) {
            failed(me.businessId(), tranId, "FAILED");
            throw new ApiException(ErrorCode.BILLING_GATEWAY_ERROR);
        }
        if (session.gatewayUrl() == null) {
            failed(me.businessId(), tranId, "FAILED");
            throw new ApiException(ErrorCode.BILLING_GATEWAY_ERROR).withDetail("reason", session.failedReason());
        }
        return new Checkout(invoiceId, number, price[0], session.gatewayUrl(), null);
    }

    private Map<String, String> sessionFields(String kind, Plans.Plan plan, int months, long amount, String tranId,
            UUID invoiceId, CurrentBusiness me) {
        UserDirectory.UserSummary owner = users.summaries(List.of(me.userId())).get(me.userId());
        BusinessProfile.Profile business = profiles.current();
        // Read when used: in tests it names the app's own (random) port.
        String apiBaseUrl = environment.getRequiredProperty("mojud.web.api-base-url");
        String returns = apiBaseUrl + "/api/v1/public/gateway/sslcommerz/return/";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("total_amount", taka(amount));
        fields.put("currency", "BDT");
        fields.put("tran_id", tranId);
        fields.put("success_url", returns + "success?invoice=" + invoiceId);
        fields.put("fail_url", returns + "fail?invoice=" + invoiceId);
        fields.put("cancel_url", returns + "cancel?invoice=" + invoiceId);
        fields.put("ipn_url", apiBaseUrl + "/api/v1/public/gateway/sslcommerz/ipn");
        fields.put("cus_name", owner.name());
        fields.put("cus_email", owner.email() != null ? owner.email() : "billing@mojud.app");
        fields.put("cus_phone", owner.phone() != null ? owner.phone() : business.phone() != null ? business.phone() : "01700000000");
        fields.put("cus_add1", business.address() == null || business.address().isBlank() ? "Dhaka" : business.address());
        fields.put("cus_city", "Dhaka");
        fields.put("cus_country", "Bangladesh");
        fields.put("shipping_method", "NO");
        fields.put("num_of_item", "1");
        fields.put("product_name", "Mojud " + plan.nameEn() + (kind.equals("UPGRADE") ? " upgrade"
                : ", " + months + (months == 1 ? " month" : " months")));
        fields.put("product_category", "Software");
        fields.put("product_profile", "non-physical-goods");
        fields.put("value_a", invoiceId.toString());
        return fields;
    }

    @Override
    public void confirm(UUID businessId, String tranId, SslCommerzClient.Payment validated) {
        TenantScope.run(businessId, () -> fresh.executeWithoutResult(status -> {
            Attempt attempt = jdbc.sql("select id, invoice_id, amount from payment_attempts where tran_id = ? for update")
                    .param(tranId)
                    .query((rs, n) -> new Attempt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3)))
                    .single();
            Invoice invoice = lockedInvoice(attempt.invoiceId());
            if (!invoice.status().equals("OPEN")) {
                return;
            }
            boolean matches = validated.paid() && tranId.equals(validated.tranId()) && "BDT".equals(validated.currency())
                    && poisha(validated.amount()) == attempt.amount();
            Instant now = clock.instant();
            if (!matches) {
                jdbc.sql("update payment_attempts set status = 'INVALID', validation = ?::jsonb, validated_at = ? where id = ?")
                        .params(asJson(validated.raw()), Timestamp.from(now), attempt.id()).update();
                audit.record(businessId, null, "payment.invalid", "invoice", invoice.id(), "tranId", tranId,
                        "status", validated.status(), "amount", validated.amount(), "currency", validated.currency());
                return;
            }
            jdbc.sql("update payment_attempts set status = 'PAID', val_id = ?, validation = ?::jsonb, validated_at = ? where id = ?")
                    .params(validated.valId(), asJson(validated.raw()), Timestamp.from(now), attempt.id()).update();
            markPaid(businessId, invoice, "SSLCOMMERZ", tranId + " / " + validated.valId(), null, now);
        }));
    }

    @Override
    public void markBankTransfer(UUID businessId, UUID invoiceId, String reference, UUID adminId) {
        TenantScope.run(businessId, () -> fresh.executeWithoutResult(status -> {
            Invoice invoice = lockedInvoice(invoiceId);
            if (!invoice.status().equals("OPEN")) {
                throw new ApiException(ErrorCode.BILLING_INVOICE_NOT_OPEN);
            }
            Instant now = clock.instant();
            jdbc.sql("""
                            insert into payment_attempts (id, business_id, invoice_id, gateway, tran_id, amount, status, created_at,
                                                          validated_at)
                            values (?, ?, ?, 'MANUAL', ?, ?, 'PAID', ?, ?)
                            """)
                    .params(Ids.newId(), businessId, invoiceId, "BANK-" + random(16), invoice.amount(), Timestamp.from(now),
                            Timestamp.from(now))
                    .update();
            markPaid(businessId, invoice, "BANK_TRANSFER", reference, adminId, now);
        }));
    }

    /**
     * The one way an invoice becomes paid: the subscription changes (a plan for some months, or an
     * upgrade), the invoice is PAID with its period, a ledger row is written, the owner is told,
     * and InvoicePaid is published. Called with the invoice row locked and OPEN.
     */
    private void markPaid(UUID businessId, Invoice invoice, String method, String reference, UUID recordedBy, Instant now) {
        Instant periodStart;
        Instant periodEnd;
        if (invoice.kind().equals("UPGRADE")) {
            subscriptions.upgrade(invoice.planCode());
            Subscriptions.Subscription subscription = subscriptions.current();
            periodStart = now;
            periodEnd = subscription.currentPeriodEnd();
        } else {
            Subscriptions.Period period = subscriptions.extend(invoice.planCode(), invoice.months());
            periodStart = period.start();
            periodEnd = period.end();
        }
        jdbc.sql("update invoices set status = 'PAID', paid_at = ?, period_start = ?, period_end = ? where id = ?")
                .params(Timestamp.from(now), Timestamp.from(periodStart), periodEnd == null ? null : Timestamp.from(periodEnd),
                        invoice.id())
                .update();
        jdbc.sql("""
                        insert into billing_ledger (id, business_id, invoice_id, type, amount, vat_amount, method, reference,
                                                    recorded_by, created_at)
                        values (?, ?, ?, 'PAYMENT', ?, ?, ?, ?, ?, ?)
                        """)
                .params(Ids.newId(), businessId, invoice.id(), invoice.amount(), invoice.vatAmount(), method, reference,
                        recordedBy, Timestamp.from(now))
                .update();
        audit.record(businessId, recordedBy, "invoice.paid", "invoice", invoice.id(), "number", invoice.number(),
                "method", method, "reference", reference, "plan", invoice.planCode(), "until", periodEnd);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", invoice.id().toString());
        payload.put("args", List.of(plans.get(invoice.planCode()).nameEn(), periodEnd == null ? "" : DAY.format(periodEnd)));
        notifier.notify("billing.paid", BusinessRole.OWNER, payload, "billing.paid:" + invoice.id());
        events.publishEvent(new InvoicePaid(businessId, invoice.id(), invoice.kind(), invoice.planCode(), invoice.amount(),
                periodEnd));
    }

    @Override
    public void failed(UUID businessId, String tranId, String status) {
        String outcome = status.equalsIgnoreCase("CANCELLED") ? "CANCELLED" : "FAILED";
        TenantScope.run(businessId, () -> fresh.executeWithoutResult(tx -> jdbc.sql("""
                        update payment_attempts set status = ?, checked_at = ? where tran_id = ? and status = 'PENDING'
                        """)
                .params(outcome, Timestamp.from(clock.instant()), tranId).update()));
    }

    InvoiceView get(UUID id) {
        return views("where id = ? and business_id = ?", id, CurrentBusiness.require().businessId()).stream().findFirst()
                .orElseThrow(ApiException::notFound);
    }

    /** Newest first. */
    List<InvoiceView> list() {
        return views("where business_id = ? order by created_at desc, id desc limit 200", CurrentBusiness.require().businessId());
    }

    private List<InvoiceView> views(String where, Object... params) {
        return jdbc.sql("""
                        select id, number, kind, plan_code, months, amount, vat_amount, status, pay_by, period_start, period_end,
                               created_at, paid_at
                        from invoices
                        """ + where)
                .params(params)
                .query((rs, n) -> new InvoiceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9),
                        instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), rs.getTimestamp(12).toInstant(),
                        instant(rs.getTimestamp(13))))
                .list();
    }

    private Invoice lockedInvoice(UUID id) {
        return jdbc.sql("""
                        select id, number, kind, plan_code, months, amount, vat_amount, status, pay_by
                        from invoices where id = ? for update
                        """)
                .param(id)
                .query((rs, n) -> new Invoice(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9)))
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private Plans.Plan publicPlan(String code) {
        return plans.all().stream().filter(p -> p.isPublic() && p.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.invalidField("planCode", "field.invalid_value"));
    }

    private static LocalDate day(Instant instant) {
        return instant.atZone(DhakaTime.ZONE).toLocalDate();
    }

    /** "1090.00" in poisha; anything unreadable is -1 (never equal to an amount). */
    static long poisha(String taka) {
        try {
            return new BigDecimal(taka.strip()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static String taka(long poisha) {
        return new BigDecimal(poisha).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private String asJson(String raw) {
        try {
            json.readTree(raw);
            return raw;
        } catch (RuntimeException e) {
            return json.writeValueAsString(Map.of("raw", raw));
        }
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
