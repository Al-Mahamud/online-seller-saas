# Orders pages redesign

Nov 2, 2026 · **Status: the orders list and the order detail page are built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

Design: [Mojud orders list design](https://claude.ai/artifact/XY6yzYYXkAmUR3XbuiHCqx) (the list, one order, and every status with its next step). No backend change.

## Orders list

Decided by Al Mahamud, on the products page: a search panel with search and reset buttons, carried over here.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Search on the button | The list changes on খুঁজুন or Enter, not while typing. Dates are always visible. The status tabs change it at once. |
| Chips | Below the panel, what's applied, each with an ×. |
| Table | Order and date, customer and phone, items, total with COD below, status. Anywhere on a row opens the order. |
| Choosing orders | A dark bar with প্যাক হয়েছে and কুরিয়ারে দিন, each only when it applies to every chosen order, and printing slips. |
| পরিশোধিত | Under the total when nothing is left to collect and the order is paid. |
| Left out | A courier column, which needs backend work. |

## Order detail

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Progress band | Each step with its time. A short bar on phones, with "now" and "next". |
| One next step | The main action stands out in the band; only what applies to the status is offered. |
| Money | With the items, ending in what's still to collect, to pay back or owed. |
| Customer | A call button. On phones this card comes first, for calling and packing. |
| Cancel | Small red text away from the other buttons, at the end of the page. |
| History | Newest first. |
| Left out | Photos beside items, which need backend work. |

**Not redrawn.** The ship, deliver, return, payment and cancel dialogs work as before.

## Different from the drawing

- **Phone header:** the app's white header stays above the dark area, as on Today.
- **Bar for chosen orders on phones:** it stays at the top of the list, under the header, not at the bottom, where the + button would cover it.
- **Times in the band:** full date and time, so they wrap to two lines on a laptop.
- **Laptop's actions:** they stack when the band is narrow.

## Known limits

- **Delivered orders** still say "COD" with the amount in the list, since that's what was to be collected.
- **Selected rows:** a status badge of the same pale green as a selected row loses its outline; the words stay readable.
- **English** was never drawn, but the text is in `en.json`.

## New text

In `frontend/src/i18n/bn.json` and `en.json` under `orders.list` and `orders.detail`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 2, 2026:** list and detail designed and built.
