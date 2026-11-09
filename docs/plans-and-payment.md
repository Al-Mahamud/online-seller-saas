# Plans and payment

Changed on Nov 2, 2026, at Al Mahamud's request: three plans, no trial. Design: https://claude.ai/artifact/NpFjNcWvwEK6ucggrZ4i4f (copy in `mojud-design-artifacts/18-plan-and-payment`).

## The plans

| | Free | Starter | Pro |
|---|---|---|---|
| Price | ৳০ | ৳৪৯৯ a month | ৳৮৯৯ a month |
| A year (ten months' price) | | ৳৪,৯৯০ | ৳৮,৯৯০ |
| Variants | 50 | 500 | 5,000 |
| Orders a month | 60 | 2,000 | Unlimited |
| Members besides the owner | 0 (the owner alone) | 10 | Unlimited |
| Reports | Last 30 days | Last 365 days | All time |
| Downloads | No | No | Yes |

Set in migration `V36__free_plan.sql`; an administrator can still edit plans.

## Rules

- **Every business starts on Free:** ACTIVE with no period, so it never ends and the daily job leaves it alone.
- **No trial:** V36 moved every business on the old trial (or read-only after it) to Free, and dropped `trial_claims`. A trial an administrator extends from before still ends on Free.
- **Paying:** from Free, any months of Starter or Pro (a checkout). During a paid period, the same plan is renewed and a dearer one is an upgrade (the prorated difference).
- **Moving down:** during a paid period, a cheaper plan (Free too) waits for the renewal; Free then starts with no grace. With no paid period running (payment due, read-only), Free starts at once.
- **A lapsed paid plan** still gets a week of grace, then read-only, as before.
- **Admins** set paid plans only (Free has no end date); admin metrics count only paid plans as paying.

## The page

Plan band (name, state, dates, the one thing to do), usage (with "just you" for Free's members), the three plans with a 1 / 6 / 12 month switch, and invoices. The payment dialog picks months and how to pay (online or bank transfer) and shows the server's quote. The invoice page is a receipt; an unpaid bank transfer shows three steps. Banners on every page now include "near this month's order limit" for managers and owners.

## bKash and Nagad in the pay dialog

Added Nov 7, 2026, at Al Mahamud's request. Design: https://claude.ai/artifact/B2L7pZ3supqi3ZYoDjTAFQ
(copy in `mojud-design-artifacts/26-bkash-and-nagad`).

"কীভাবে দেবেন" has four choices: **bKash** and **Nagad** as two tiles (bKash chosen first), **card or
another way** (Rocket, Upay, card, internet banking, chosen on SSLCommerz's page) and **bank transfer**.
The pay button and the note under it name the wallet: "বিকাশে ৳… দিন".

- **Server:** checkout and upgrade take an optional `wallet` (`BKASH` or `NAGAD`, with SSLCOMMERZ only;
  with a bank transfer it is a 400). It becomes the session's `multi_card_name`, so SSLCommerz's page
  opens on that wallet. The names are settings, `mojud.billing.sslcommerz.wallets.bkash` (`bkash`) and
  `.nagad` (`mobilebank`). The wallet is kept in the activity log's "invoice opened" entry.
- **Nagad:** SSLCommerz documents a name for bKash but none for Nagad (Nov 2026), so Nagad opens
  SSLCommerz's mobile wallets and the seller taps Nagad there; its tile says so. If SSLCommerz gives
  Nagad's own name, set `mojud.billing.sslcommerz.wallets.nagad`. SSLCommerz's docs also mark
  `multi_card_name` "Do not Use!"; if the live gateway ignores it, its page simply shows every way.
- **A wallet the gateway refuses:** the dialog says "নগদে এখন দেওয়া যাচ্ছে না…" and the tile shows
  "এখন বন্ধ" until the dialog closes.
- **Texts** load with the plan page (`src/i18n/pages/pay.*.json`), as the main file is at the size budget.

## Different from the drawing

- **Upgrade breakdown:** the dialog shows the total and how it's worked out in words, not the two lines (the server's quote has only the total and VAT).
- **Bank details** are the server's text, shown as it is.
- **Payment method on invoices:** online or bank transfer, not which wallet paid (the gateway's answer has it; not shown yet).
- **Wallet marks** (Nov 7): a letter on the wallet's colour (বি, ন), not the bKash and Nagad logos; their logo files come from the merchant kits, under their rules.
- **Nagad's tile** (Nov 7) says "SSLCommerz-এর পাতায় নগদ বেছে নিন" rather than "সরাসরি নগদের পাতায়", as it opens the mobile wallets (above).
- **A refused wallet** (Nov 7) is one line in the dialog's red box, not a heading and a line.

## Trying payments in the SSLCommerz sandbox

The sandbox is SSLCommerz's test gateway: its pages look real, but no money moves. Mojud uses it
whenever `SSLCOMMERZ_BASE_URL` isn't set, so going live is only a change of address and store.

1. **Get a sandbox store:** register at https://developer.sslcommerz.com/registration/. SSLCommerz
   emails a Store ID and Store Password (for the sandbox only).
2. **Local run (IntelliJ):** in the backend's run configuration, add the environment variables
   `SSLCOMMERZ_STORE_ID` and `SSLCOMMERZ_STORE_PASSWORD`, then restart. IntelliJ keeps them in
   `backend/.idea/workspace.xml`, which git ignores. Never put them in `application-local.yml`.
3. **Pay:** sign in as an owner, choose a plan and "pay online". SSLCommerz's sandbox page opens.
   Pay with a test card from SSLCommerz's developer docs (at the time of writing: VISA
   `4111 1111 1111 1111`, Mastercard `5111 1111 1111 1111`, any future expiry, CVV `111`; mobile OTP
   `111111` or `123456`), or pick a test wallet.
4. **Back in Mojud** the invoice turns paid within seconds.

**How it confirms:** SSLCommerz's IPN can't reach `localhost`, so on a local run the payment is
confirmed when the browser comes back: the backend asks SSLCommerz's Validation API about that
`val_id` and applies it only if the gateway says this attempt's `tran_id` was paid with the right
amount (since Nov 5, 2026; servers benefit too when an IPN is slow). If the browser never comes
back, the pending check asks the gateway after 30 minutes.

**Not the sandbox:** browser and backend tests (`spring-boot:test-run`, `TestMojudApplication`) use
the stand-in gateway in test sources (`FakeSslCommerz`), so they never need SSLCommerz.

**A server on the sandbox** (a staging server) starts with the warning "SSLCOMMERZ_BASE_URL is the
sandbox". Going live: `SSLCOMMERZ_BASE_URL=https://securepay.sslcommerz.com` and the live store's
ID and password in `.env` (deploy/.env.example).

## Known limits

- **Old prices:** invoices paid before keep their amounts; a paid period running at the old price runs to its end, then renews at the new one.
- **Near-limit banner** is checked against orders only.
- **Tests:** most backend and browser tests put their business on Pro (`TestBusinesses.create`, `signUpWithBusiness`), as they aren't about plans; billing tests start on Free (`createOnFree`, `plan: "FREE"`).
- **Bangla text** needs Al Mahamud's read-through (`docs/bangla-review.csv`).

## Change log

- **Nov 2, 2026:** three plans, no trial; plan and payment pages redesigned.
- **Nov 5, 2026:** the trial promise left on sign-in and sign-up is gone. The admin overview drops its "on trial" and "trial to paying" tiles. A business still on a trial from before (none exist yet) can still have it extended from its admin page.
- **Nov 5, 2026:** the SSLCommerz sandbox works on a local run: a payment is confirmed when the browser returns from the gateway (validated with SSLCommerz), not only by the IPN. Servers on the sandbox warn at start.
- **Nov 7, 2026:** bKash and Nagad as their own choices in the pay dialog.
- **Nov 7, 2026:** the payment notification reads in Bangla for Bangla readers ("৭ ডিসেম্বর ২০২৬ পর্যন্ত আপনি স্টার্টার প্ল্যানে আছেন"; notifications can carry `args_bn`). Downloads on Free open "আপনার প্ল্যানে এটি নেই", not "…সীমা শেষ".
