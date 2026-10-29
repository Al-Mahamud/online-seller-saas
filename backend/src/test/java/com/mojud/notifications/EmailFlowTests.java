package com.mojud.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.billing.system.SubscriptionAdvancer;
import com.mojud.catalog.system.LowStockDigestJob;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.CapturedMessages;
import com.mojud.support.CrossTenant;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestAdmins;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Who gets which email (implementation-plan.md 13a): billing reminders and receipts go to owners,
 * the low-stock digest to managers and owners who have not turned it off, each in their language.
 */
@IntegrationTest
class EmailFlowTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    SubscriptionAdvancer advancer;

    @Autowired
    LowStockDigestJob digest;

    @Autowired
    TestClock clock;

    ApiClient owner;
    TestAccounts.Account ownerAccount;
    TestAccounts.Account managerAccount;
    TestAccounts.Account staffAccount;
    UUID business;

    @BeforeEach
    void team() {
        owner = new ApiClient(port);
        ownerAccount = TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Rina's Boutique");
        managerAccount = join("MANAGER");
        staffAccount = join("STAFF");
    }

    @Test
    void billingRemindersGoToOwnersOnceADayInTheirLanguage() {
        systemDatabase.jdbc().sql("update users set locale = 'bn' where email = ?").param(ownerAccount.email()).update();
        systemDatabase.jdbc().sql("update subscriptions set trial_ends_at = ? where business_id = ?")
                .params(Timestamp.from(clock.instant().plus(Duration.ofDays(3)).plus(Duration.ofHours(1))), business).update();

        advancer.advance(business);
        advancer.advance(business);

        List<CapturedMessages.Message> reminders = emails(ownerAccount, "ফ্রি ট্রায়াল");
        assertThat(reminders).hasSize(1);
        assertThat(reminders.get(0).subject()).isEqualTo("Rina's Boutique: আপনার ফ্রি ট্রায়াল ৩ দিনে শেষ হবে");
        assertThat(reminders.get(0).html()).contains("/billing");
        assertThat(emails(managerAccount, "trial")).isEmpty();
        assertThat(emails(staffAccount, "trial")).isEmpty();
    }

    @Test
    void aPaidInvoiceSendsTheOwnerAReceipt() {
        String invoice = owner.post("/api/v1/billing/checkout", Map.of("planCode", "PRO", "months", 6, "payBy", "BANK_TRANSFER"))
                .expectStatus(201).json("$.invoiceId");
        String number = owner.get("/api/v1/billing/invoices/" + invoice).json("$.number");

        TestAdmins.signIn(port, messages, systemDatabase, clock)
                .post("/api/v1/admin/billing/invoices/" + invoice + "/mark-paid", Map.of("reference", "DBBL-1")).expectStatus(200);

        List<CapturedMessages.Message> receipts = emails(ownerAccount, "receipt for invoice");
        assertThat(receipts).hasSize(1);
        assertThat(receipts.get(0).subject()).isEqualTo("Rina's Boutique: receipt for invoice " + number);
        assertThat(receipts.get(0).body()).contains("৳17,940").contains("Pro");
        assertThat(emails(managerAccount, "receipt")).isEmpty();
    }

    @Test
    void theLowStockDigestGoesToManagersAndOwnersWhoWantIt() {
        owner.post("/api/v1/products", Map.of("name", "Jamdani Saree", "price", 4500)).expectStatus(201);

        digest.run();

        assertThat(emails(ownerAccount, "running low")).hasSize(1);
        assertThat(emails(ownerAccount, "running low").get(0).body()).contains("Jamdani Saree");
        assertThat(emails(managerAccount, "running low")).hasSize(1);
        assertThat(emails(staffAccount, "running low")).isEmpty();

        // The manager turns it off (for themself only); the owner still gets it.
        ApiClient manager = signIn(managerAccount);
        assertThat(manager.get("/api/v1/notifications/preferences").<Boolean>json("$.lowStockDigestEmail")).isTrue();
        manager.put("/api/v1/notifications/preferences", Map.of("lowStockDigestEmail", false)).expectStatus(200);
        signIn(staffAccount).get("/api/v1/notifications/preferences").expectStatus(403);

        digest.run();

        assertThat(emails(ownerAccount, "running low")).hasSize(2);
        assertThat(emails(managerAccount, "running low")).hasSize(1);
    }

    @Test
    void nothingLowNoDigest() {
        digest.run();
        assertThat(emails(ownerAccount, "running low")).isEmpty();
    }

    private TestAccounts.Account join(String role) {
        ApiClient member = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(member, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", role)).expectStatus(201);
        member.post("/api/v1/invites/accept", Map.of("token", CrossTenant.lastInviteToken(messages, account.email())))
                .expectStatus(200);
        return account;
    }

    private ApiClient signIn(TestAccounts.Account account) {
        ApiClient client = new ApiClient(port);
        client.post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password())).expectStatus(200);
        return client;
    }

    private List<CapturedMessages.Message> emails(TestAccounts.Account account, String subjectPart) {
        return messages.to(account.email()).stream()
                .filter(message -> message.subject() != null && message.subject().contains(subjectPart)).toList();
    }
}
