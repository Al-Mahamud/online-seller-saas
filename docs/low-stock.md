# Running low (স্টক কম)

Running low is a window over whatever page is open, not a page of its own. Design:
https://claude.ai/artifact/TWn4mMbnu1GVCpdbhib2bY (local copy in `mojud-design-artifacts/19-running-low`).

## How it opens

- `?lowStock=true` on any signed-in address opens it (`_app` route's `validateSearch`, `LowStockWindow`),
  so the browser's Back closes it and a link can open it anywhere.
- `LowStockLink` opens it over the current page: the products list's "স্টক কম: Nটি" chip, and Today's
  problem tile and "সব দেখুন" link.
- `/products/low-stock` (the low-stock email's link) redirects to `/products?lowStock=true`.
- The dialog code loads only when opened (`lazy`), so it costs the first load nothing.

## Inside

- **List:** out-of-stock and low counts in the dark header; search by name or SKU; tabs সব/শেষ/কম;
  rows with a tick, stock bar, alert level, SKU and "মাল এসেছে". It loads every row (100 at a time).
- **মাল এসেছে** (one row, or the ticked ones): a quantity per line, starting at enough to reach twice
  the alert level, with 5/10/20 quick picks and "এরপর থাকবে". Saves one stock adjustment with RECEIVE
  lines (`POST /api/v1/stock/adjustments`, idempotency key), so it shows in each product's stock history.
- **কেনার তালিকা** (the ticked ones): quantities to buy, same starting rule; copy, or open WhatsApp
  with the text.

Everyone in the business can use all of it; changing an alert level stays on the product page (managers).

## Not done

- The starting quantity is a simple rule. Basing it on recent sales needs a new API field.
