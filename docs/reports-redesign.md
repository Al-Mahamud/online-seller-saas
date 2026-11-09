# Report page redesign

Nov 2, 2026 · **Status: built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

Design: [Mojud report page design](https://claude.ai/artifact/1u5uPFXFDMkrfsf1drjGgp). No backend change.

Decided by Al Mahamud: a pie chart.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Dark band | The days, then sales, net profit, orders and items sold. Profit is for managers and owners only. |
| Comparison | A line under sales comparing with the same number of days before. Left out if there was nothing before, or the plan doesn't reach that far. |
| বিক্রির ভাগ | A pie by product or category, the first card, with names, shares and amounts beside it. The top five get a slice, and the rest share one grey slice. One product gets a sentence instead. |
| Profit pie | Cost of goods, write-offs and profit on goods, as parts of the sales money. Left out when there's no profit on goods, since a pie can't show less than nothing. |
| Column chart | Kept below the pie, since a pie can't show sales over days. A column a day up to 45 days, then 7 days, then 30 days, counted from the first day chosen. |
| Table | Day, product or category. On phones, orders, items and delivery go under the name, so it no longer scrolls sideways. |
| Loss | The net profit box turns red and says মোট ক্ষতি. |
| Downloads | One full row each. |

## Different from the drawing

- **The chart's columns:** the drawing had one button per column. Thirty columns are too thin to tap one by one and fail the 24 px minimum, so the whole chart is one slider: point, tap or use the arrow keys, and a screen reader hears each day's figures.
- **A note under the pie:** only when it adds up to more than the sales, since an order's discount isn't split across its items.

## Known limits

- **Slice colours** go by rank, so a product can change colour when the days change. Keeping one colour per product would need more colours than a pie can keep apart.
- **Sales here aren't Today's sales:** this page's are the value of the goods; Today's include delivery charges.
- **Weeks and "months"** in the chart are runs of 7 and 30 days from the first day chosen, not calendar weeks and months.
- **Staff** see the sales pie, but no profit, cost or downloads, as before.
- **English** was never drawn, but the text is in `en.json`.

## Code

`features/reports/charts.ts` has the sums, tested in `charts.test.ts`, and `ReportCharts.tsx` draws them. The comparison's query sets `meta: { optional: true }` (`api/query.ts`), so a refusal by the plan never opens the upgrade dialog for days nobody asked for. Slice colours passed the palette checks for lightness, contrast and colour blindness.

## New text

In `frontend/src/i18n/bn.json` and `en.json` under `reports`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 2, 2026:** designed and built, with pie charts at Al Mahamud's request.
- **Nov 2, 2026:** brought back in line with the drawing at Al Mahamud's request: the best day sits beside the sales heading, the chart's top figure is inside the chart (drawn over the columns so a tall one can't hide it), the sales pie is 220 px on a laptop and 200 px on a phone, and returned-and-damaged goods always show in the profit card, at ৳০ when there are none.
- **Nov 7, 2026:** sales by product and by category share each order's discount over its items by their value (to the paisa), so they add up to the total sales; they were at full item prices, ৳১০০ apart from the total on one discounted order. Further back than the plan reaches says "আপনার প্ল্যানে গত … দিনের রিপোর্ট দেখা যায়…" rather than the general limit message, and the page no longer asks for a comparison period the plan can't reach (`reportDays` in GET /api/v1/billing/status).
