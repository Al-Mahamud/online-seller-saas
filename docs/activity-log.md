# Activity log (কার্যক্রমের তালিকা)

`/settings/audit-log`, owners only. Design: https://claude.ai/artifact/WiS9191TmExbpgCRvU9v3x
(local copy in `mojud-design-artifacts/24-activity-log`).

## Server

`GET /api/v1/business/audit-log?actor=&kind=&from=&to=&q=&page=&size=`

- `actor`: a member's id, or `SYSTEM` for what nobody did by hand.
- `kind`: ORDERS, STOCK, PRODUCTS, CUSTOMERS, TEAM, SETTINGS or PLAN, worked out from the action in
  SQL (`AuditLogController.KIND`); each entry carries its `kind`.
- `from`, `to`: days in Dhaka, both included. `q`: words in the details (order numbers, SKUs, product
  and customer names, file names).
- Each entry's `actor.member` says whether they're still in the team; `subject` is the name of the
  person an entry is about (staff entries).

`GET /api/v1/business/audit-log/summary`: today and the last 7 days, who did how much and what kind,
and `actors` (everyone who appears, for the person filter).

Two entries now record a name to show: `variant.prices_changed` (`product`) and `customer.deleted`
(`name`). Older entries without them show the SKU, or nothing.

## Page

Dark header with today's and 7 days' counts; search, person, period (default 7 days) and kind chips,
kept in the address. Entries grouped by day, each a sentence (`activity.verb.<group>.<name>`, with
the action code as a fallback for one not yet worded), linking to the order or invoice, with a
coloured kind and a short detail; "কী বদলেছে" opens before → after for changes. A side panel on
laptop (who and what, 7 days) filters when tapped. Numbered pages below the list, kept in the
address (the shared pager, `docs/pagination.md`); a new filter goes back to page 1.

## Texts that load with their page

To stay within the 250 KB first-visit budget, the activity log's and the team page's words live in
`src/i18n/pages/activity.*.json`, `team.*.json` and `pay.*.json` (the plan page's pay dialog), loaded by those routes' loaders
(`src/i18n/pages.ts`, `loadPageTexts`); they use `useLooseT` since their keys are outside the typed
main file. The Bangla review list and the translation tests include them. The budget has about
0.7 KB left: a big new block of text for one page belongs there too.

## Change log

- Nov 4, 2026: numbered pages (20, 50 or 100 rows) instead of the "আগের কাজ দেখুন" button.
- Nov 7, 2026: a stock change shows its reason in words ("দাগ বা রং নষ্ট"), not its code (STAIN).
