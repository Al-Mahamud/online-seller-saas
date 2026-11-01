# Dashboard redesign

Nov 1, 2026 · **Status: built, as described under "As built". Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

Design: [Mojud dashboard design](https://claude.ai/artifact/NFMpqCmyhCBf8A88kCMgCZ) (laptop, phone and a new shop, in Bangla and English).

## What the page answers

| Section | Question | Shows |
|---|---|---|
| Order journey (অর্ডারের পথ) | Where are my orders? | Draft, confirmed, packed and with courier now, each with its next step; delivered, returned and cancelled that day |
| Needs attention (নজর দিন) | What's going wrong? | Only problems: late parcels, COD stuck with a courier, low stock. One line when there are none |
| Business (ব্যবসার হিসাব) | How is the business doing? | Sales, profit (managers and owners), COD outstanding, seven days of sales |
| Delivery and returns (ডেলিভারি ও ফেরত) | Which courier is costing me? | 30 days: delivered against returned, each courier's return rate, why parcels came back |
| Products (পণ্য) | What's selling, what's running out? | The three best sellers and the three lowest in stock |

The old tiles and the list of orders by status are gone: the journey shows the same counts.

## Proposed by Claude

Change any of these by saying so.

| Proposal | Detail |
|---|---|
| Late parcel | On the way for more than 5 days |
| Stuck COD | Collected, not paid out, delivered more than 7 days ago. One row per courier |
| Delivery window | Parcels shipped in the last 30 Dhaka days |
| Return rate | Parcels that came back ÷ (delivered + came back). Parcels still on the way don't count |
| Highest rate | Marked in bold, when there are two or more couriers |
| New shop | No orders yet: the three steps replace the page. Only for managers and owners who can write; anyone else sees the journey and the zero figures |

## As built

**Backend.** `GET /api/v1/dashboard/overview`, for every member: `lateParcels`, `stuckCod`, `couriers`, `returnReasons`, `hasProducts`, `hasOrders`, and the three numbers above. Separate from `/dashboard/today` because it doesn't change with the day on screen. No migration.

**Figures to read with care.**
- A courier's "on the way" is every parcel on the road now, however old, so it adds up to the journey's "with courier". Its delivered and returned count the 30-day window only.
- "Why they came back" counts returns on parcels in the window. A parcel the customer partly refused, or returned later, stays delivered in the table, so the three reasons can add up to more than "returned".

**Different from the drawing.**
- **Phone header:** the app's white header (business, bell, language) stays above the dark area, as on every other screen. The drawing put the logo and business inside the dark area.
- **Another day on phones:** the date field stays in the dark header. The drawing had none, but the app can already look at any day.
- **All clear:** the line for no problems wasn't drawn.
- **Courier table on phones:** the same headings as on a laptop, wrapping where needed, not the shortened "On way" and "Rate".
- **Late parcels' link:** goes to all orders with couriers. The orders list can't yet show only the late ones.

## New text

All of it is in `frontend/src/i18n/bn.json` and `en.json` under `today`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Tests

- `DashboardTests`: a worked example of late parcels, stuck COD, the window and the reasons. `CrossTenantTests` has a case for the new endpoint.
- `e2e/reports.spec.ts`: the figures on screen against the API, the journey's links, a new shop's steps, the courier table, the all-clear line and English, with the accessibility scan.
- The browser tests can't age a parcel, so late parcels and stuck COD are checked in `DashboardTests`, and their rows were only seen with made-up answers from the API.

## Change log

- **Nov 1, 2026:** designed for laptop, phone and a new shop, then in English.
- **Nov 1, 2026:** built in the backend and the web app.
- **Nov 1, 2026:** the page fills the width on big displays, at Al Mahamud's request, keeping the app's usual 40 px margin. It was held at 1040 px, which left wide empty sides.
