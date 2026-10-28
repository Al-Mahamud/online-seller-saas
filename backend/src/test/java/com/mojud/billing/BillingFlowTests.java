package com.mojud.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.billing.system.SubscriptionAdvancer;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.FakeSslCommerz;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Upgrades, downgrades at renewal, bank transfers, the ledger and invoices (implementation-plan.md 11b). */
@IntegrationTest
class BillingFlowTests {

    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    FakeSslCommerz gateway;

    @Autowired
    SubscriptionAdvancer advancer;

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
        business = TestBusinesses.create(owner, "Billing Shop");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void anUpgradePaysTheProratedDifferenceAndKeepsThePeriodEnd() {
        // Starter, a 30-day month: 10 days gone, 20 left.
        LocalDate today = LocalDate.now(DHAKA);
        Instant start = today.minusDays(10).atStartOfDay(DHAKA).toInstant();
        Instant end = today.plusDays(20).atStartOfDay(DHAKA).toInstant();
        paidPeriod("STARTER", start, end);

        ApiResponse upgrade = owner.post("/api/v1/billing/upgrade", Map.of("planCode", "PRO")).expectStatus(201);

        // ৳2,000 × 20 ÷ 30 = ৳1,333.33, rounded up to ৳1,334.
        assertThat(upgrade.<Integer>json("$.amount")).isEqualTo(133400);
        String invoice = upgrade.json("$.invoiceId");
        assertThat(gateway.session(gateway.tranIdOf(invoice))).containsEntry("product_name", "Mojud Pro upgrade");
        pay(invoice, "1334.00");

        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.plan.code")).isEqualTo("PRO");
        assertThat(Instant.parse(billing.json("$.currentPeriodEnd"))).isEqualTo(end);
        ApiResponse paid = owner.get("/api/v1/billing/invoices/" + invoice);
        assertThat(paid.<String>json("$.kind")).isEqualTo("UPGRADE");
        assertThat(paid.<Integer>json("$.months")).isZero();

        owner.post("/api/v1/billing/upgrade", Map.of("planCode", "STARTER")).expectError(400, "validation.failed");
    }

    @Test
    void anUpgradeNeedsARunningPaidPeriod() {
        owner.post("/api/v1/billing/upgrade", Map.of("planCode", "PRO")).expectError(409, "billing.payment_needed");
    }

    @Test
    void aDowngradeTakesEffectAtTheRenewal() {
        Instant end = Instant.now().plus(Duration.ofDays(5));
        paidPeriod("PRO", Instant.now().minus(Duration.ofDays(25)), end);
        owner.put("/api/v1/billing/plan", Map.of("planCode", "STARTER")).expectStatus(200);
        owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectError(409, "billing.use_upgrade");

        clock.advance(Duration.ofDays(5).plusMinutes(1));
        advancer.advance(business);

        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.status")).isEqualTo("GRACE");
        assertThat(billing.<String>json("$.plan.code")).isEqualTo("STARTER");
        assertThat(billing.<Object>json("$.pendingPlanCode")).isNull();
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        pay(invoice, "990.00");
        assertThat(owner.get("/api/v1/billing").<String>json("$.status")).isEqualTo("ACTIVE");
    }

    @Test
    void aBankTransferIsMarkedPaidByAnAdminThroughTheSameSteps() {
        ApiResponse checkout = owner.post("/api/v1/billing/checkout", Map.of("planCode", "PRO", "months", 6,
                "payBy", "BANK_TRANSFER")).expectStatus(201);
        String invoice = checkout.json("$.invoiceId");
        assertThat(checkout.<Object>json("$.gatewayUrl")).isNull();
        assertThat(checkout.<String>json("$.bankTransfer")).contains("Reference: " + checkout.json("$.number"));
        assertThat(checkout.<Integer>json("$.amount")).isEqualTo(1794000);

        ApiClient admin = admin();
        assertThat(admin.get("/api/v1/admin/billing/invoices?status=OPEN").<List<String>>json("$[*].id")).contains(invoice);
        admin.post("/api/v1/admin/billing/invoices/" + invoice + "/mark-paid", Map.of("reference", "DBBL-778812"))
                .expectStatus(200);

        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).isEqualTo("PAID");
        ApiResponse billing = owner.get("/api/v1/billing");
        assertThat(billing.<String>json("$.status")).isEqualTo("ACTIVE");
        assertThat(billing.<String>json("$.plan.code")).isEqualTo("PRO");
        assertThat(owner.get("/api/v1/notifications").<String>json("$.items[0].title")).startsWith("Payment received. You are on Pro");
        admin.post("/api/v1/admin/billing/invoices/" + invoice + "/mark-paid", Map.of("reference", "again"))
                .expectError(409, "billing.invoice_not_open");
        owner.post("/api/v1/admin/billing/invoices/" + invoice + "/mark-paid", Map.of("reference", "me")).expectStatus(403);
    }

    @Test
    void theLedgerHoldsExactlyThePaidInvoices() {
        String card = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        pay(card, "990.00");
        String bank = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 12,
                "payBy", "BANK_TRANSFER")).expectStatus(201).json("$.invoiceId");
        owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201);
        admin().post("/api/v1/admin/billing/invoices/" + bank + "/mark-paid", Map.of("reference", "BRAC-1")).expectStatus(200);

        long ledger = systemDatabase.jdbc().sql("select sum(amount) from billing_ledger where business_id = ?")
                .param(business).query(Long.class).single();
        long paid = systemDatabase.jdbc().sql("select sum(amount) from invoices where business_id = ? and status = 'PAID'")
                .param(business).query(Long.class).single();
        assertThat(ledger).isEqualTo(paid).isEqualTo(99000 + 990000);

        String csv = new String(admin().download("/api/v1/admin/billing/ledger.csv").body(), StandardCharsets.UTF_8);
        List<String> mine = csv.lines().filter(line -> line.contains(business.toString())).toList();
        assertThat(mine).hasSize(2);
        assertThat(mine).anyMatch(line -> line.contains(",SSLCOMMERZ,") && line.endsWith(",990.00,0.00"));
        assertThat(mine).anyMatch(line -> line.contains(",BANK_TRANSFER,BRAC-1,9900.00,0.00"));
        assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo("﻿Date,Invoice,Business,Type,Method,Reference,Amount,VAT");
    }

    @Test
    void invoicesAreListedAndPrintInBothLanguages() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201)
                .json("$.invoiceId");
        pay(invoice, "990.00");
        owner.post("/api/v1/billing/checkout", Map.of("planCode", "STARTER", "months", 1)).expectStatus(201);

        assertThat(owner.get("/api/v1/billing/invoices").<List<String>>json("$[*].status")).containsExactly("OPEN", "PAID");

        var page = owner.download("/api/v1/billing/invoices/" + invoice + "/print?lang=en");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValue(InvoicePages.CONTENT_SECURITY_POLICY);
        String en = new String(page.body(), StandardCharsets.UTF_8);
        assertThat(en).contains("<h2>Invoice</h2>", "Billing Shop", "Mojud Starter, 1 month", "<p class=\"status paid\">Paid</p>",
                "<tr class=\"total\"><th>Total</th><td class=\"n\">৳990</td></tr>", "Paid on ");
        String bn = new String(owner.download("/api/v1/billing/invoices/" + invoice + "/print?lang=bn").body(), StandardCharsets.UTF_8);
        assertThat(bn).contains("<h2>ইনভয়েস</h2>", "মজুদ স্টার্টার, ১ মাস", "পরিশোধিত", "৳৯৯০");
    }

    /** The gateway's IPN for the invoice's attempt, paid in full. */
    private void pay(String invoice, String amount) {
        String tranId = gateway.tranIdOf(invoice);
        sslcommerz.postForm("/api/v1/public/gateway/sslcommerz/ipn", Map.of("status", "VALID", "tran_id", tranId,
                "val_id", gateway.pay(tranId, amount, "BDT", "VALID"), "amount", amount, "currency", "BDT")).expectStatus(200);
        assertThat(owner.get("/api/v1/billing/invoices/" + invoice).<String>json("$.status")).isEqualTo("PAID");
    }

    private void paidPeriod(String plan, Instant start, Instant end) {
        systemDatabase.jdbc().sql("""
                        update subscriptions set plan_code = ?, status = 'ACTIVE', current_period_start = ?, current_period_end = ?
                        where business_id = ?
                        """)
                .params(plan, Timestamp.from(start), Timestamp.from(end), business).update();
    }

    /** A platform administrator, signed in afresh (the role is read at sign-in). */
    private ApiClient admin() {
        ApiClient admin = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(admin, messages);
        systemDatabase.jdbc().sql("update users set is_platform_admin = true where email = ?").param(account.email()).update();
        ApiClient fresh = new ApiClient(port);
        fresh.post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password())).expectStatus(200);
        return fresh;
    }
}
