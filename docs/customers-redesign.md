# Customer pages redesign

Nov 2, 2026 · **Status: the customer list, one customer's page and the customer form are built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

Design: [Mojud customer pages design](https://claude.ai/artifact/DSk4HSasJ4FRmhcuFooHus). No backend change.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Width | Full width, like the other lists. |
| Search | A dark panel with খুঁজুন and রিসেট, and a chip. The list changes on the button or Enter, not while typing, as on Orders and Products. |
| List on a laptop | A table of customer, phone number, area, orders and last order. Anywhere on a row opens the customer. |
| List on phones | Rows with a call button, so a customer can be rung without opening their page. |
| One customer | A dark header with the name, phone, orders, last order and when they became a customer, and নতুন অর্ডার, ফোন করুন and তথ্য বদলান. |
| Two columns | Orders, then contact details and the note, from 1024 px. Below that, orders come first. |
| Note | In its own coloured box. |
| গ্রাহক মুছুন | A red button, like cancelling an order. A refusal now stays in the dialog and says why, where it used to close the dialog and show a toast. |
| Form | The same dark-header frame as Change stock, full screen on phones. Name and phone share a row from 768 px. |
| Empty lists | No customers and no orders each offer the next step. |

## Different from the drawing

- **Rows per page and pager:** the shared pager with 20, 50 or 100 rows, not the 8 rows and simpler pager drawn to fit the board.
- **Status colours:** the app's own status labels.
- **Phone numbers:** in Bangla digits when the app is in Bangla, as everywhere else in it.
- **Refusing to delete:** the server's own message, not the drawing's longer one.

## Known limits

- **Drafts don't count as orders:** the order count and last order date go up when an order is confirmed, as they always have. A customer with only drafts shows ০টি and "no orders yet" on the list, though their page lists the drafts.
- **Total spent** isn't shown, since it needs the backend.
- **English** was never drawn, but the text is in `en.json`.

## Code

`components/layout/SearchPanel.tsx` holds the dark search panels and chips that Orders, COD and customers share. `LedgerDialog` now has an optional summary, since this form has nothing to sum up.

## New text

In `frontend/src/i18n/bn.json` and `en.json` under `customers`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 2, 2026:** designed and built.
