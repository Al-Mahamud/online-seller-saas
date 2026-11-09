# Orders pages redesign

Nov 1, 2026 · **Status: the orders list, the order detail page, the new order page and the delivered and take items back modals are built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

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
| Cancel | Decided by Al Mahamud: a red button, darker red on hover, so it's easy to see. It stays away from the other buttons, at the end of the page. |
| History | Newest first. |
| Left out | Photos beside items, which need backend work. |

**Not redrawn.** The ship, deliver, return, payment and cancel dialogs work as before.

## New order

Decided by Al Mahamud: no search box on the products card. A **+ পণ্য যোগ করুন** button at its top right opens a modal with products on the left; clicking one shows its variants on the right; clicking selects a variant; OK closes the modal and puts the items in the list.

Design: [Mojud new order design](https://claude.ai/artifact/LMc8edfaqtfqK5ntYhCuah). The same form changes an order, so "অর্ডার বদলান" looks the same, without the advance fields. Nothing about what's saved changes.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Customer on top | The phone number first, in the dark band. A known customer shows name and order count; an unknown number asks for a name. |
| Two columns | From a laptop up: address, products and note on the left; the money and the save buttons on the right, staying in view. |
| Products as rows | Quantity, price, line total and remove, with the stock warning on the row. A message says when nothing's been added. |
| In the modal | A search above the product list. Tick several variants across products before OK; each product shows how many are ticked. New items start at quantity 1. |
| Already on the order | Ticked and locked, marked "আগেই যোগ করা আছে". The × in the list removes one. |
| Out of stock | Can still be ticked, marked "স্টক নেই", as before. |
| Modal on phones | Full screen: the products, then one product's variants with a back arrow. The ticked count and OK stay at the bottom. |
| Money like a receipt | Product price, delivery, discount, total, advance, then COD. Payment method and transaction ID only once there's an advance. |
| Phone bar | COD and the save button stay on screen above the menu. খসড়া রাখুন is at the end of the page. |
| Left out | Photos in the rows, because saved orders keep none for each item. The modal's product list has them. |

**Not redrawn.** The district and area pickers work as before.

**Different from the drawing:**

- **District and area on phones** stack instead of sharing a row, since each needs room for its clear button.
- **Stock in the modal** uses the app's existing wording, "বিক্রয়যোগ্য ১২", not "১২টি আছে".
- **Clicking outside the modal** does nothing, so a stray click can't lose what's ticked. Close and বাতিল do close it, and add nothing.
- **The phone bar while typing:** no code hides it. Browsers that open the keyboard over the page, the default in current Android Chrome and iPhone Safari, cover it along with the menu. An older Android browser that shrinks the page instead would keep the bar above the keyboard. Not yet tried on a real phone.

## COD to settle

Decided by Al Mahamud: one courier at a time, with tabs to switch between them.

Design: [Mojud COD to settle design](https://claude.ai/artifact/QYZqLLawfMnC4wK9dp63Ju). No backend change.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Totals on top | A dark band with what couriers owe, how many parcels, and what's still on the way. |
| Courier tabs | Underlined on a laptop, swipeable buttons on phones. Each tab shows what that courier owes, or "পথে ৳…" with only parcels on the way. |
| Which opens | The courier owed the most comes first and opens first. |
| Ticks and settling | What's unticked stays when switching tabs. A courier with nothing left to settle or on the way loses its tab, and another opens. |
| One courier's card | What it owes and what's on the way, with a short card when only parcels are on the way. |
| Parcels | A table from a laptop up: order, tracking number, delivery date, amount collected. Rows on phones. |
| Settling | Everything starts ticked, with a tick box for all of a courier's parcels. The button shows the amount, and the confirmation stays. |
| Shortfall | A row says "আদায়ের কথা ছিল ৳…" when the courier collected less than expected. |
| Staff | See the amounts, without ticks or the button, as before. |

### Search and pages

Decided by Al Mahamud: add a search panel and pagination.

Design: [Mojud COD search and pages design](https://claude.ai/artifact/5PZTZXisHS5KSNAr5RxuPo). This one needs the backend: `/api/v1/shipments/cod/summary`, `/api/v1/shipments/cod/parcels` and `/api/v1/shipments/settle-cod/matching`.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Search | Order number, tracking number, name or phone, and the days of delivery, in the dark band under the totals, with chips below. |
| Totals | The band always shows everything couriers hold. A search only changes the tabs and the courier's card. |
| Tabs under a search | Only couriers with a match, showing what was found. |
| Rows | The customer's name under the order number. |
| What a payout covers | Everything the search found, on every page, less what's unticked. Parcels the search didn't find are never part of it. |
| Ticks | Stay when changing page. A new search or another courier starts again with everything ticked, which replaces "unticked stays when switching tabs" above. |
| The button | At the top of the courier's card, so it's reachable without scrolling past the rows. |
| If the list changed | The server compares the count and the amount with what was shown. If they differ nothing is saved, and the confirmation stays with the new figures. |

**Different from the drawing:** the confirmation's refusal doesn't repeat the figures, because the text and button above it already show them.

**Known limits:** a partly delivered order also shows the shortfall note, since less was collected than first expected. Past payouts aren't on this page, the same as before. Reloading, or coming back from an order, keeps the courier, search and page but ticks everything again. One payout can cover at most 10,000 parcels when most are ticked, or 500 when everything was unticked and chosen one by one. The older `/api/v1/shipments` list is still capped at 500, and this page no longer uses it.

## Delivered and take items back

Design: [Mojud delivered and take back design](https://claude.ai/artifact/E7ZWftgNRLcrVAhXB5G4M1). No backend change. The same sums as before, and the same requests. The code is in `features/orders/ReturnDialogs.tsx`, on the frame in `components/layout/LedgerDialog.tsx`, which Change stock now uses too.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Frame | Dark header with the marigold line, a body that scrolls, and a footer with a live summary. The whole screen on phones. |
| Delivered | গ্রাহক কি সব পণ্য নিয়েছেন? with two answers, in place of a checkbox. The items only show for না. |
| টাকার হিসাব | What the courier was to collect, what refused items take off, and what to collect now, above the amount collected. |
| Amount warning | A note when the amount collected isn't what was to be collected. It doesn't stop saving. |
| Quantity | − and + buttons, up to the most allowed. No typing. |
| What becomes of an item | Two buttons, স্টকে ফেরত and নষ্ট, স্টকে নয়, once the quantity is above zero. |
| Take items back | Items, then the reason, then the money. Refund, method and transaction ID as before, only when something can be refunded. |
| Parcel came back | The same modal, with no quantities and no money, since the whole parcel came back. |

## Different from the drawing

- **না, কিছু নেননি with nothing chosen:** the drawing would have saved it. It now asks which items, because saving would record that everything was taken.
- **"Off for refused items":** says না নেওয়া পণ্যের জন্য বাদ, not দাম, and shows what actually came off the amount to collect, which can be less than the price when part was paid before.
- **A typed amount:** stays when the items change, where the drawing reset it. The warning says when it no longer matches. A refund typed earlier is dropped if its box goes away.
- **Reason's error:** এটি পূরণ করুন, the app's usual text.

- **Phone header:** the app's white header stays above the dark area, as on Today.
- **Bar for chosen orders on phones:** it stays at the top of the list, under the header, not at the bottom, where the + button would cover it.
- **Times in the band:** full date and time, so they wrap to two lines on a laptop.
- **Laptop's actions:** they stack when the band is narrow.

## Known limits

- **Delivered orders** still say "COD" with the amount in the list, since that's what was to be collected.
- **Selected rows:** a status badge of the same pale green as a selected row loses its outline; the words stay readable.
- **English** was never drawn, but the text is in `en.json`.
- **Large quantities** in the delivered and take items back modals take one tap each, because these have no box to type in.

## New text

In `frontend/src/i18n/bn.json` and `en.json` under `orders.list`, `orders.detail` and `orders.form`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 1, 2026:** list and detail designed and built.
- **Nov 1, 2026:** new order designed and built, which also changes the page for changing an order.
- **Nov 1, 2026:** the search on the products card replaced by the add items button and modal.
- **Nov 1, 2026:** the cancel order button changed from small red text to a red button.
- **Nov 1, 2026:** COD to settle designed and built.
- **Nov 1, 2026:** COD to settle changed from every courier on the page to one at a time, with tabs.
- **Nov 1, 2026:** the delivered, take items back and parcel came back modals designed and built.
- **Nov 1, 2026:** COD to settle gets a search panel and pages. Its totals now come from the server, which fixes them being too low past 500 parcels.
- **Nov 7, 2026:** fixes after a walk-through as a new seller. The order note's hint now says it prints on the slip too ("স্লিপেও ছাপা হবে, কুরিয়ার আর গ্রাহকও দেখবেন।"): the slip has always printed it, for the courier, and the old hint promised only the team would see it. The cancel dialog's other button says "রেখে দিন" (it said "বাতিল" beside "অর্ডার বাতিল করুন"). Times read "বিকাল ৫:১৬", not "৫:১৬ PM", everywhere in Bangla.
