package com.mojud.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.billing.system.PendingPayments;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.FakeSslCommerz;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Paying through SSLCommerz (implementation-plan.md 11a) against the fake gateway in test sources:
 * checkout, the gateway's IPN (sent here as the gateway would), validation, and the subscription.
 */
@IntegrationTest
class CheckoutTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    FakeSslCommerz gateway;

    @Autowired
    PendingPayments pendingPayments;

    @Autowired
    TestClock clock;

    ApiClient owner;
    ApiClient sslcommerz;
    UUID business;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        sslcommerz = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Paying Shop");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void aValidPaymentExtendsThePeriodOnce() {
        Instant trialEnds = Instant.parse(owner.get("/api/v1/billing").json("$.trialEndsAt"));
        ApiResponse checkout = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201);
        String invoice = checkout.json("$.invoiceId");
        assertThat(checkout.<String>json("$.number")).startsWith("MJ-");
        assertThat(checkout.<Integer>json("$.amount")).isEqualTo(99000);
        assertThat(checkout.<String>json("$.gatewayUrl")).startsWith("https://sandbox.sslcommerz.com/");
        String tranId = gateway.tranIdOf(invoice);
        Map<String, String> session = gateway.session(tranId);
        assertThat(session).containsEntry("total_amount", "990.00").containsEntry("currency", "BDT")
                .containsEntry("store_id", "test-store").containsEntry("product_profile", "non-physical-goods");
        assertThat(session.get("ipn_url")).endsWith("/api/v1/public/gateway/sslcommerz/ipn");
        assertThat(session.get("success_url")).endsWith("/return/success?invoice=" + invoice);
        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).isEqualTo("OPEN");

        Map<String, String> ipn = ipn(tranId, gateway.pay(tranId, "990.00", "BDT", "VALID"), "990.00");
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn", ipn).expectStatus(200);

        ApiResponse paid = owner.get("/api/v1/billing/invoices/" + invoice);
        assertThat(paid.<String>json("$.status")).isEqualTo("PAID");
        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(billing.<String>json("$.plan.code")).isEqualTo("STARTER");
        // Paid during the trial: the month starts when the trial ends, so no trial day is lost.
        Instant end = Instant.parse(billing.json("$.currentPeriodEnd"));
        assertThat(end).isEqualTo(trialEnds.atZone(ZoneId.of("Asia/Dhaka")).plusMonths(1).toInstant());
        assertThat(owner.get("/api/v1/notifications").<String>json("$.items[0].title")).startsWith("Payment received. You are on Starter until");

        // The same IPN again (gateways retry): nothing changes.
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn", ipn).expectStatus(200);
        assertThat(Instant.parse(owner.get("/api/v1/billing").json("$.currentPeriodEnd"))).isEqualTo(end);
        assertThat(callbacks(tranId)).isEqualTo(2);
    }

    @Test
    void aWrongAmountOrCurrencyIsRefused() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);

        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn",
                ipn(tranId, gateway.pay(tranId, "10.00", "BDT", "VALID"), "990.00")).expectStatus(200);

        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).isEqualTo("OPEN");
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("TRIALING");
        assertThat(attemptStatus(tranId)).isEqualTo("INVALID");

        String other = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        String otherTran = gateway.tranIdOf(other);
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn",
                ipn(otherTran, gateway.pay(otherTran, "990.00", "USD", "VALID"), "990.00")).expectStatus(200);
        assertThat(attemptStatus(otherTran)).isEqualTo("INVALID");
    }

    @Test
    void aForgedIpnFailsValidation() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "PRO", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);

        // "VALID", the right amount, but a val_id the gateway never issued.
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn", ipn(tranId, "MADE-UP", "2990.00")).expectStatus(200);

        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).isEqualTo("OPEN");
        assertThat(attemptStatus(tranId)).isEqualTo("INVALID");
        // An IPN for a tran_id nobody has is stored and answered, and changes nothing.
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn", ipn("NOPE", "MADE-UP", "1.00")).expectStatus(200);
        assertThat(callbacks("NOPE")).isEqualTo(1);
    }

    @Test
    void aFailedOrCancelledPaymentIsNoted() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);

        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn", Map.of("status", "CANCELLED", "tran_id", tranId))
                .expectStatus(200);

        assertThat(attemptStatus(tranId)).isEqualTo("CANCELLED");
        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).isEqualTo("OPEN");
    }

    @Test
    void aLostIpnIsFoundByThePendingCheck() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 6)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);
        gateway.pay(tranId, "5940.00", "BDT", "VALID");

        pendingPayments.run();
        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).as("not yet: 30 minutes").isEqualTo("OPEN");

        clock.advance(Duration.ofMinutes(31));
        pendingPayments.run();

        ApiResponse paid = owner.get("/api/v1/billing/invoices/" + invoice);
        assertThat(paid.<String>json("$.status")).isEqualTo("PAID");
        assertThat(paid.<Integer>json("$.months")).isEqualTo(6);
    }

    @Test
    void anUnansweredPaymentIsGivenUpAfterTwoDays() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);

        clock.advance(Duration.ofDays(2).plusMinutes(1));
        pendingPayments.run();

        assertThat(attemptStatus(tranId)).isEqualTo("FAILED");
    }

    @Test
    void aReadOnlyBusinessPaysAndCanWorkAgain() {
        systemDatabase.jdbc().sql("update subscriptions set status = 'READ_ONLY', read_only_since = ? where business_id = ?")
                .params(Timestamp.from(Instant.now()), business).update();
        owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectError(402, "subscription.read_only");

        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "PRO", "months", 12)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);
        // A year costs ten months.
        assertThat(gateway.session(tranId)).containsEntry("total_amount", "29900.00");
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn",
                ipn(tranId, gateway.pay(tranId, "29900.00", "BDT", "VALID"), "29900.00")).expectStatus(200);

        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.status")).isEqualTo("ACTIVE");
        Instant end = Instant.parse(billing.json("$.currentPeriodEnd"));
        assertThat(Duration.between(Instant.now(), end)).isBetween(Duration.ofDays(364), Duration.ofDays(367));
        owner.post("/api/v1/products", Map.of("name", "Kurti", "price", 100)).expectStatus(201);
    }

    @Test
    void whileAPeriodRunsPaymentsExtendTheSamePlan() {
        Instant end = ZonedDateTime.now().plusDays(10).toInstant().truncatedTo(ChronoUnit.SECONDS);
        systemDatabase.jdbc().sql("""
                        update subscriptions set plan_code = 'STARTER', status = 'ACTIVE', current_period_start = ?,
                               current_period_end = ? where business_id = ?
                        """)
                .params(Timestamp.from(Instant.now().minus(Duration.ofDays(20))), Timestamp.from(end), business).update();

        owner.post("/api/v1/billing/checkout", Map.of("planCode", "PRO", "months", 1)).expectError(409, "billing.use_upgrade");
        owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 3)).expectError(400, "validation.failed");
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn",
                ipn(tranId, gateway.pay(tranId, "990.00", "BDT", "VALID"), "990.00")).expectStatus(200);

        ApiResponse paid = owner.get("/api/v1/billing/invoices/" + invoice);
        assertThat(Instant.parse(paid.json("$.periodStart"))).isEqualTo(end);
    }

    @Test
    void theGatewayRefusingIsReportedAndOnlyTheOwnerPays() {
        gateway.refuseNextSession("Store is de-active");
        ApiResponse refused = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1))
                .expectError(502, "billing.gateway_error");
        assertThat(refused.<String>json("$.reason")).isEqualTo("Store is de-active");

        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "MANAGER")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);
        staff.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(403);
    }

    @Test
    void theBrowsersReturnOnlyRedirects() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        String tranId = gateway.tranIdOf(invoice);

        ApiResponse back = sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/return/success?invoice=" + invoice,
                Map.of("status", "VALID", "tran_id", tranId, "val_id", "whatever", "amount", "990.00"));

        assertThat(back.status()).isEqualTo(303);
        assertThat(back.headers().firstValue("Location")).hasValue("http://localhost:5173/billing/return?result=success&invoice=" + invoice);
        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).isEqualTo("OPEN");
        assertThat(callbacks(tranId)).isEqualTo(1);
    }

    private static Map<String, String> ipn(String tranId, String valId, String amount) {
        return Map.of("status", "VALID", "tran_id", tranId, "val_id", valId, "amount", amount, "currency", "BDT",
                "card_type", "BKASH-BKash", "verify_sign", "ignored");
    }

    private String attemptStatus(String tranId) {
        return systemDatabase.jdbc().sql("select status from payment_attempts where tran_id = ?").param(tranId)
                .query(String.class).single();
    }

    private long callbacks(String tranId) {
        return systemDatabase.jdbc().sql("select count(*) from gateway_callbacks where tran_id = ?").param(tranId)
                .query(Long.class).single();
    }
}
