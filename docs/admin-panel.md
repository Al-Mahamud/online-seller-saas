# Admin panel (অ্যাডমিন)

`/admin`, platform admins only. Redesigned Nov 5, 2026, at Al Mahamud's request. Design:
https://claude.ai/artifact/WDRFp79wbT1gbCxaSZFvq1 (local copy in `mojud-design-artifacts/25-admin-panel`).

Who gets in: `is_platform_admin`, plus two-step sign-in on servers (local runs skip it:
`mojud.admin.require-two-factor`, `docs/auth-redesign.md`). The layout asks the server first and
shows no data until it agrees.

## Pages

| Address | What it shows |
|---|---|
| `/admin` | **সারসংক্ষেপ**, the first page: monthly revenue and paying businesses; "এখন যা দেখতে হবে" (bank transfers waiting, businesses in grace or read-only, suspended), each linking to its list; businesses, users, active this week, ever paid; businesses on each plan; the five newest businesses. |
| `/admin/businesses` | Search (name, or the owner's phone, email or user ID), plan and state chips (`?plan=`, `?state=`), numbered pages. A table on a laptop, a list on a phone. |
| `/admin/businesses/{id}` | Plan, period end and next renewal; usage with bars; invoices; the owner with user ID, phone and email; this business's admin actions. Give a plan, suspend (or lift a suspension), and extend a trial only for a business still on one. |
| `/admin/invoices` | Bank transfers waiting (with how long) or paid (`?tab=paid`), "পরিশোধিত লিখুন" asking for the bank's reference, and the month's ledger. |
| `/admin/plans` | Each plan with its price, how many businesses are on it and its limits; editing opens below the cards. Hidden plans come last. |
| `/admin/audit` | Every admin action by day, as sentences, with chips for bank transfers, plans set, suspensions and plan edits (`?kind=`). |

The sidebar (a menu behind ☰ on a phone) has the five pages, the count of transfers waiting,
back to the app, the language and the admin with sign-out. The old `/admin/metrics` page is gone:
its figures are on the overview.

## Server

- `GET /api/v1/admin/metrics` adds `grace`, `plans` (`{code, businesses}`, cheapest first: every
  offered plan and any other a business is on), `waitingTransfers`, `waitingPoisha` and
  `oldestWaiting`.
- `GET /api/v1/admin/businesses` takes `plan` (a plan code; anything else is a 400) and `state`
  (ACTIVE, GRACE, READ_ONLY, SUSPENDED or TRIALING). The owner now carries `userId`.
- `PUT /api/v1/admin/businesses/{id}/plan` takes an optional `reason`, kept in the admin log.
- `GET /api/v1/admin/audit` takes `action`, repeatable; each entry carries `businessName`.
  "Marked paid" entries now keep the invoice's `number` and `amount` with the reference.
- Invoices in the admin views carry `months`.

## Different from the drawing

- **Bank transfers' header** shows the total waiting only, not "paid this month": the server
  doesn't count that yet.
- **The paid tab** says when the transfer was asked for, not when it was paid.
- **Plan badges** use the plans' English names (Free, Starter, Pro) in both languages; the plans
  page shows both names.

## Change log

- **Nov 5, 2026:** built in the backend and the web app.
- **Nov 7, 2026:** plan names in Bangla on a Bangla screen (ফ্রি, স্টার্টার, প্রো), and online payments read "অনলাইন (SSLCommerz)" rather than SSLCOMMERZ.
