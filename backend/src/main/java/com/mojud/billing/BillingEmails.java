package com.mojud.billing;

import com.mojud.notifications.MemberMail;
import com.mojud.shared.email.EmailComposer;
import com.mojud.shared.email.EmailMessage;
import com.mojud.shared.money.Taka;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessProfile;
import com.mojud.tenancy.BusinessRole;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Billing emails to the owners (implementation-plan.md 13a): each reminder or warning the
 * lifecycle raises in the app, and a receipt when an invoice is paid. Sent with the in-app
 * notification, so as often as it is (once per kind a day).
 */
@Component
class BillingEmails {

    private final MemberMail mail;
    private final EmailComposer composer;
    private final BusinessProfile profiles;
    private final Plans plans;
    private final String webBaseUrl;

    BillingEmails(MemberMail mail, EmailComposer composer, BusinessProfile profiles, Plans plans,
            @Value("${mojud.web.base-url}") String webBaseUrl) {
        this.mail = mail;
        this.composer = composer;
        this.profiles = profiles;
        this.plans = plans;
        this.webBaseUrl = webBaseUrl;
    }

    /** {@code type} is a notification type ({@code billing.trial_ending}, ...) with its args. */
    void reminder(String type, List<Object> args) {
        String shop = profiles.current().name();
        mail.send(BusinessRole.OWNER, null, locale -> reminderEmail(composer.in(locale), type, args, shop, webBaseUrl));
    }

    /** Snapshots in BillingEmailTests. */
    static EmailMessage reminderEmail(EmailComposer.Draft draft, String type, List<Object> args, String shop,
            String webBaseUrl) {
        return draft.subject("email." + type + ".subject", args.isEmpty() ? 0 : args.get(0), shop)
                .paragraph("notification." + type, args.toArray())
                .paragraph("email.billing.shop", shop)
                .action("email.billing.action", webBaseUrl + "/billing")
                .build();
    }

    void receipt(UUID invoiceId, String number, String planCode, long amount, Instant paidUntil) {
        String shop = profiles.current().name();
        Plans.Plan plan = plans.get(planCode);
        mail.send(BusinessRole.OWNER, null, locale -> receiptEmail(composer.in(locale), invoiceId, number,
                "bn".equals(locale.getLanguage()) ? plan.nameBn() : plan.nameEn(), amount, paidUntil, shop, webBaseUrl));
    }

    /** {@code amount} in poisha, VAT included (snapshots in BillingEmailTests). */
    static EmailMessage receiptEmail(EmailComposer.Draft draft, UUID invoiceId, String number, String planName, long amount,
            Instant paidUntil, String shop, String webBaseUrl) {
        Locale locale = draft.locale();
        String until = paidUntil == null ? "" : day(paidUntil, locale);
        String taka = "৳" + ("bn".equals(locale.getLanguage()) ? Taka.banglaDigits(Taka.lakh(amount)) : Taka.lakh(amount));
        return draft.subject("email.receipt.subject", number, shop)
                .paragraph("notification.billing.paid", planName, until)
                .table(List.of("email.receipt.invoice", "email.receipt.plan", "email.receipt.amount", "email.receipt.until"),
                        List.of(List.of(number, planName, taka, until)))
                .paragraph("email.billing.shop", shop)
                .action("email.receipt.action", webBaseUrl + "/billing/invoices/" + invoiceId)
                .build();
    }

    /** 28 Oct 2026, or ২৮ অক্টোবর ২০২৬, in Dhaka. */
    static String day(Instant instant, Locale locale) {
        return DateTimeFormatter.ofPattern("bn".equals(locale.getLanguage()) ? "d MMMM yyyy" : "d MMM yyyy", locale)
                .withDecimalStyle(DecimalStyle.of(locale)).withZone(DhakaTime.ZONE).format(instant);
    }
}
