# Damaged goods page

Built on Nov 2, 2026, at Al Mahamud's request, from the design at https://claude.ai/artifact/FcQ7fZun76tQnofZ9hQJsV (copy in `mojud-design-artifacts/16-damaged-products`). Menu: `নষ্ট পণ্য`, after `পণ্য` in the sidebar and first on the phone's More page.

## What it shows

| Part | What's in it |
|---|---|
| Header | The days (last 7, last 30, this month, or any dates), a product search, and the totals: items damaged against the days before, loss at cost (managers and owners), from the shelf, and back from customers. |
| List | Each damage, newest first: date and who recorded it, product and variant, where it came from (the shelf, or a return with a link to its order), the reason and note, quantity and loss. Filter: all, shelf, returns. A table on laptops, cards on phones, 20 to a page. |
| Most lost | Managers and owners: the five products that lost the most, how many products have no cost price, and how this relates to the profit report. |
| Record | A dialog: search products, add any variant that can be sold, set how many, pick what happened, add a note. Totals in its header. All lines are saved together, or none. |

## Rules

- **Two sources:** shelf damage is a stock adjustment (`DAMAGE`); damaged returns are return items written off. The list reads both.
- **Loss at cost:** shelf damage at the product's cost price now; returns at the order's cost price. A product with no cost price shows no loss and is counted under "no cost price".
- **Reason:** required in the dialog (broken or torn, stained or faded, lost, expired, something else). Kept on stock movements (`reason`, migration `V34`), for damage only. Damage recorded earlier, or from a product's "change stock", has none.
- **Who:** every member can see the page and record damage. Money (loss, cost, most lost) only for managers and owners, in the API too.
- **Days:** the same rules as reports: last 30 days by default, at most 366, not past the plan's report days. The comparison with the days before is left out when the plan doesn't reach them.

## Different from the drawing

- **Search panel:** uses the shared dated search panel from Orders and COD, so the search box has its own row above the dates and buttons, instead of one row with the dates.
- **Picking products:** a product found opens to show its variants, each added on its own. The drawing showed variants straight away.
- **Reason:** nothing is picked when the dialog opens; saving asks for one.
- **Dates in the list** show the time as well.

## Known limits

- **Old cost prices:** shelf damage is valued at today's cost price, so a later change of cost price changes past losses. Returns keep the order's.
- **Worked out whole:** the list and totals are worked out from every damage in the days chosen, then cut into pages. Fine for the hundreds a shop records; a shop with tens of thousands in a year would want it done in the database.
- **No undo:** the stock ledger can't be edited. A mistake is put right by receiving the stock back on the product page.
- **Bangla text** needs Al Mahamud's read-through (`docs/bangla-review.csv`).

## Code

- Backend: `reporting/DamageReports` and `DamageController` (`GET /api/v1/damages`, `GET /api/v1/damages/summary`), from `inventory/StockQueries.damages`, `orders/OrderReports.writeOffs`, `catalog/CatalogReports.variantsMatching`. `reporting/ReportRange` holds the date rules for every report. `shared/text/Text.containsPattern` is the one LIKE pattern for searches.
- Frontend: `features/damages/` (page, dialog, queries, address filters), route `/damages`. Shared: `components/form/Choice` (also on Reports), `LedgerDialog`'s `figures`.
- Tests: `reporting/DamageTests`, cross-business cases in `CrossTenantTests`, `e2e/damages.spec.ts`.

## Change log

- **Nov 2, 2026:** designed and built.
- **Nov 7, 2026:** a damage written from a product's stock dialog (a note, no reason picked) no longer shows "কারণ লেখা নেই" beside its note. The record dialog's "add at least one product" and "pick a reason" messages go once they are answered.
