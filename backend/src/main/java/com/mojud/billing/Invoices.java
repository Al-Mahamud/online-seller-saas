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
 * Paying for a plan (implementation-plan.md 11a, backend-plan.md §9). Checkout opens an invoice
 * and a payment attempt and returns the gateway's page; the browser's return proves nothing. Only
 * the gateway's validated answer (from its IPN, or the pending-payment check) marks the invoice
 * paid, once, in one transaction with the subscription's extension.
 */
@Service
class Invoices implements PaymentConfirmations {

    static final Set<Integer> MONTHS = Set.of(1, 6, 12);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(DhakaTime.ZONE);

    /** What the owner sees while the gateway's answer is awaited, and after. Money in poisha. */
    record InvoiceView(UUID id, String number, String kind, String planCode, int months, long amount, long vatAmount,
            String status, Instant periodStart, Instant periodEnd, Instant createdAt, Instant paidAt) {
    }

    record Checkout(UUID invoiceId, String number, long amount, String gatewayUrl) {
    }

    /** Published when an invoice is paid, inside that transaction. */
    record InvoicePaid(UUID businessId, UUID invoiceId, String planCode, Instant periodEnd) {
    }

    private record Attempt(UUID id, UUID invoiceId, long amount, String status) {
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

    /** Price for {@code months}: monthly times months, a year for the price of ten (placeholder); then VAT. */
    long[] price(Plans.Plan plan, int months) {
        long base = plan.priceMonthly() * (months == 12 ? 10 : months);
        long vat = new BigDecimal(base).multiply(vatRate).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new long[] {base + vat, vat};
    }

    /**
     * Opens an invoice and asks the gateway for its page. The invoice is saved first (committed),
     * so the gateway's answer always finds it; a gateway refusal marks the attempt failed.
     */
    Checkout checkout(String planCode, int months) {
        CurrentBusiness me = CurrentBusiness.require();
        if (!gateway.configured()) {
            throw new ApiException(ErrorCode.BILLING_GATEWAY_UNAVAILABLE);
        }
        if (!MONTHS.contains(months)) {
            throw ApiException.invalidField("months", "field.invalid_value");
        }
        Plans.Plan plan = plans.all().stream().filter(p -> p.isPublic() && p.code().equals(planCode)).findFirst()
                .orElseThrow(() -> ApiException.invalidField("planCode", "field.invalid_value"));
        Subscriptions.Subscription subscription = subscriptions.current();
        boolean running = subscription.status().equals("ACTIVE");
        if (running && !plan.code().equals(subscription.planCode())) {
            // While a paid period runs, payments extend that plan; changing plans is an upgrade (11b)
            // or a downgrade at the renewal.
            throw new ApiException(ErrorCode.BILLING_USE_UPGRADE);
        }
        long[] price = price(plan, months);
        UUID invoiceId = Ids.newId();
        String tranId = "MJ" + random(18);
        String number = transactions.execute(status -> {
            Instant now = clock.instant();
            String invoiceNumber = "MJ-" + String.format("%06d", jdbc.sql("select nextval('invoice_numbers')").query(Long.class).single());
            jdbc.sql("""
                            insert into invoices (id, business_id, number, kind, plan_code, months, amount, vat_amount, status,
                                                  created_by, created_at)
                            values (?, ?, ?, 'SUBSCRIPTION', ?, ?, ?, ?, 'OPEN', ?, ?)
                            """)
                    .params(invoiceId, me.businessId(), invoiceNumber, plan.code(), months, price[0], price[1], me.userId(),
                            Timestamp.from(now))
                    .update();
            jdbc.sql("""
                            insert into payment_attempts (id, business_id, invoice_id, gateway, tran_id, amount, status, created_at)
                            values (?, ?, ?, 'SSLCOMMERZ', ?, ?, 'PENDING', ?)
                            """)
                    .params(Ids.newId(), me.businessId(), invoiceId, tranId, price[0], Timestamp.from(now))
                    .update();
            audit.record(me.businessId(), me.userId(), "invoice.opened", "invoice", invoiceId, "number", invoiceNumber,
                    "plan", plan.code(), "months", months, "amount", price[0]);
            return invoiceNumber;
        });

        SslCommerzClient.Session session;
        try {
            session = gateway.session(sessionFields(plan, months, price[0], tranId, invoiceId, me));
        } catch (RuntimeException e) {
            failed(me.businessId(), tranId, "FAILED");
            throw new ApiException(ErrorCode.BILLING_GATEWAY_ERROR);
        }
        if (session.gatewayUrl() == null) {
            failed(me.businessId(), tranId, "FAILED");
            throw new ApiException(ErrorCode.BILLING_GATEWAY_ERROR).withDetail("reason", session.failedReason());
        }
        return new Checkout(invoiceId, number, price[0], session.gatewayUrl());
    }

    private Map<String, String> sessionFields(Plans.Plan plan, int months, long amount, String tranId, UUID invoiceId,
            CurrentBusiness me) {
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
        fields.put("product_name", "Mojud " + plan.nameEn() + ", " + months + (months == 1 ? " month" : " months"));
        fields.put("product_category", "Software");
        fields.put("product_profile", "non-physical-goods");
        fields.put("value_a", invoiceId.toString());
        return fields;
    }

    @Override
    public void confirm(UUID businessId, String tranId, SslCommerzClient.Payment validated) {
        TenantScope.run(businessId, () -> fresh.executeWithoutResult(status -> {
            Attempt attempt = lockedAttempt(tranId);
            String invoiceStatus = jdbc.sql("select status from invoices where id = ? for update")
                    .param(attempt.invoiceId()).query(String.class).single();
            if (invoiceStatus.equals("PAID")) {
                return;
            }
            boolean matches = validated.paid() && tranId.equals(validated.tranId()) && "BDT".equals(validated.currency())
                    && poisha(validated.amount()) == attempt.amount();
            Instant now = clock.instant();
            if (!matches) {
                jdbc.sql("update payment_attempts set status = 'INVALID', validation = ?::jsonb, validated_at = ? where id = ?")
                        .params(asJson(validated.raw()), Timestamp.from(now), attempt.id()).update();
                audit.record(businessId, null, "payment.invalid", "invoice", attempt.invoiceId(), "tranId", tranId,
                        "status", validated.status(), "amount", validated.amount(), "currency", validated.currency());
                return;
            }
            jdbc.sql("update payment_attempts set status = 'PAID', val_id = ?, validation = ?::jsonb, validated_at = ? where id = ?")
                    .params(validated.valId(), asJson(validated.raw()), Timestamp.from(now), attempt.id()).update();
            record Invoice(String planCode, int months) {
            }
            Invoice invoice = jdbc.sql("select plan_code, months from invoices where id = ?").param(attempt.invoiceId())
                    .query((rs, n) -> new Invoice(rs.getString(1), rs.getInt(2))).single();
            Subscriptions.Period period = subscriptions.extend(invoice.planCode(), invoice.months());
            jdbc.sql("update invoices set status = 'PAID', paid_at = ?, period_start = ?, period_end = ? where id = ?")
                    .params(Timestamp.from(now), Timestamp.from(period.start()), Timestamp.from(period.end()), attempt.invoiceId())
                    .update();
            audit.record(businessId, null, "invoice.paid", "invoice", attempt.invoiceId(), "tranId", tranId,
                    "plan", invoice.planCode(), "until", period.end());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("invoiceId", attempt.invoiceId().toString());
            payload.put("args", List.of(plans.get(invoice.planCode()).nameEn(), DAY.format(period.end())));
            notifier.notify("billing.paid", BusinessRole.OWNER, payload, "billing.paid:" + attempt.invoiceId());
            events.publishEvent(new InvoicePaid(businessId, attempt.invoiceId(), invoice.planCode(), period.end()));
        }));
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
        return jdbc.sql("""
                        select id, number, kind, plan_code, months, amount, vat_amount, status, period_start, period_end,
                               created_at, paid_at
                        from invoices where id = ? and business_id = ?
                        """)
                .params(id, CurrentBusiness.require().businessId())
                .query((rs, n) -> new InvoiceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                        instant(rs.getTimestamp(9)), instant(rs.getTimestamp(10)), rs.getTimestamp(11).toInstant(),
                        instant(rs.getTimestamp(12))))
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private Attempt lockedAttempt(String tranId) {
        return jdbc.sql("select id, invoice_id, amount, status from payment_attempts where tran_id = ? for update")
                .param(tranId)
                .query((rs, n) -> new Attempt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                        rs.getString(4)))
                .single();
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
