package com.mojud.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.email.EmailComposer;
import com.mojud.support.IntegrationTest;
import com.mojud.support.Snapshots;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Billing reminders and the receipt in both languages (implementation-plan.md 13a), against reviewed snapshots. */
@IntegrationTest
class BillingEmailTests {

    static final String WEB = "https://app.mojud.example";
    static final UUID INVOICE = UUID.fromString("01a1833c-e2b9-773b-930d-1a351c3f0b06");

    @Autowired
    EmailComposer composer;

    @Test
    void remindersMatchTheirSnapshots() {
        for (String lang : List.of("en", "bn")) {
            Locale locale = Locale.forLanguageTag(lang);
            Snapshots.matchesEmail("trial-ending-" + lang,
                    BillingEmails.reminderEmail(composer.in(locale), "billing.trial_ending", List.of(3L), "Rina's Boutique", WEB));
            Snapshots.matchesEmail("read-only-" + lang,
                    BillingEmails.reminderEmail(composer.in(locale), "billing.read_only", List.of(), "Rina's Boutique", WEB));
        }
    }

    @Test
    void everyReminderHasASubjectInBothLanguages() {
        for (String type : List.of("billing.trial_ending", "billing.period_ending", "billing.trial_ended", "billing.grace",
                "billing.read_only", "billing.deletion_warning")) {
            for (String lang : List.of("en", "bn")) {
                String subject = BillingEmails.reminderEmail(composer.in(Locale.forLanguageTag(lang)), type, List.of(1L), "Shop",
                        WEB).subject();
                assertThat(subject).as(type + " " + lang).startsWith("Shop: ").doesNotContain("email.");
            }
        }
    }

    @Test
    void theReceiptMatchesItsSnapshots() {
        Instant until = Instant.parse("2027-04-28T05:30:00Z");
        Snapshots.matchesEmail("receipt-en", BillingEmails.receiptEmail(composer.in(Locale.ENGLISH), INVOICE, "MJ-2026-00042",
                "Pro", 1794000, until, "Rina's Boutique", WEB));
        Snapshots.matchesEmail("receipt-bn", BillingEmails.receiptEmail(composer.in(Locale.forLanguageTag("bn")), INVOICE,
                "MJ-2026-00042", "প্রো", 1794000, until, "রিনার বুটিক", WEB));
    }
}
