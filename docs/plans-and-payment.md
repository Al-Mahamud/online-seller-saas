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

## Different from the drawing

- **Upgrade breakdown:** the dialog shows the total and how it's worked out in words, not the two lines (the server's quote has only the total and VAT).
- **Bank details** are the server's text, shown as it is.
- **Payment method on invoices:** online or bank transfer; the gateway doesn't say which wallet.

## Known limits

- **Old prices:** invoices paid before keep their amounts; a paid period running at the old price runs to its end, then renews at the new one.
- **Near-limit banner** is checked against orders only.
- **Tests:** most backend and browser tests put their business on Pro (`TestBusinesses.create`, `signUpWithBusiness`), as they aren't about plans; billing tests start on Free (`createOnFree`, `plan: "FREE"`).
- **Bangla text** needs Al Mahamud's read-through (`docs/bangla-review.csv`).

## Change log

- **Nov 2, 2026:** three plans, no trial; plan and payment pages redesigned.
- **Nov 5, 2026:** the trial promise left on sign-in and sign-up is gone. The admin overview drops its "on trial" and "trial to paying" tiles. A business still on a trial from before (none exist yet) can still have it extended from its admin page.
