# Product pages redesign

Nov 1, 2026 · **Status: the new product page, the product detail page, the change stock dialog and the products list are built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

Design: [Mojud new product design](https://claude.ai/artifact/JnTNBkY6Jz3BCPpFqNExAD) (laptop, phone, and the problems a form can show).

## Decisions

Decided by Al Mahamud:

| Decision | Detail |
|---|---|
| Details | The fields stay: name, description, category, tags. |
| Variants replace options | One row per variant: name, size, quantity, buying price, selling price. A product can have many. |
| No profit column | Drawn at first, then removed. |

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Name and size | One name field, for a colour or a type, with size beside it. There is no separate colour field. |
| New row | Copies the name and prices of the row above; size and quantity start empty. |
| Same name and size twice | Refused, ignoring capitals. |
| Selling below the buying price | A warning. It can still be saved, for a sale. |
| Required | The selling price. Buying price and quantity are optional. |
| Name and size empty | Fine for a product's only variant. With several, each needs at least one. |
| SKU | Not on this page: the app makes one. It can still be changed on the product page. |

## As built

**Backend.**
- `POST /api/v1/products` takes `variants`, one row each with its own `name`, `size`, `price`, `costPrice` and `quantity`. Not every combination has to exist. The old `options`, `price`, `costPrice`, `sku` and `openingStock` still work when `variants` is left out, which the import and most tests use.
- `POST /api/v1/products/{id}/variants` adds one variant.
- `PATCH /api/v1/variants/{id}` also takes `name` and `size`; an empty one removes it.
- A row's name and size are stored as options called `Variant` and `Size`, so labels read "Red / M". No migration.

**Product page.** The options editor is gone, because saving it would have made every missing combination. In its place: "ভ্যারিয়েন্ট যোগ করুন" on the variant list, and name and size in a variant's edit dialog.

**Different from the drawing.**
- **Phone header:** the app's white header stays above the dark area, as on Today.
- **Delete button:** hidden while there is only one row.
- **Icons:** the warning for selling below cost uses a warning triangle in the working form.

## Product detail page

Design: [Mojud product detail design](https://claude.ai/artifact/SR693NNu38bj6UG9gAPgXz) (laptop, phone, and the edit dialog). Built on `product-variants`.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Details open in a dialog | The page reads first, so nothing changes by accident. Changing them is "বিবরণ বদলান". |
| Totals at the top | On hand, set aside and available add up the active variants only, with the selling price range. |
| No profit | Margin is gone from the variant rows, following the decision on the new product page. The API still sends it. |
| Photos | A small photo shows large; for managers, the large one opens the arrange dialog. |

**Backend.** Each variant in `GET /api/v1/products/{id}` now says `lowStock`, using its own alert level or the business's. An archived variant is never low. No migration.

**Different from the drawing.**
- **Phone header:** the app's white header stays above the dark area, as on Today.
- **Table width:** the variant table starts at 1120 px wide, since the sidebar takes room. Narrower, each variant is a card, not a table that scrolls sideways.
- **Archived rows:** a tinted background instead of faded text, so the text stays readable.
- **Photo rules:** the line about file types and size stays for managers.
- **Empty values:** "no description" and "no tags" lines weren't drawn.

**Not redrawn.** The stock, photo, history and variant dialogs work as before.

## Change stock dialog

Design: [Mojud change stock design](https://claude.ai/artifact/8hvkLYvbxFpHzWaw6ryTjw) (laptop, phone, and its problems). Built on `product-variants`.

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Three cards | What happened: + new stock came, − damaged or lost, = counted. |
| On hand after | Each row shows the stock it will leave as it's typed; the footer adds them up. |
| Problems on the row | The server's rules, shown before saving: damage up to what's available, a count down to what's set aside. |
| Cancel | A button on laptops. On phones the dialog fills the screen and the cross closes it. |

**No backend change.** The server still checks everything. If stock changed while the dialog was open, its refusal shows above the form and the rows take the latest numbers.

## Products list

Design: [Mojud product list design](https://claude.ai/artifact/5RhXt6upQdQrPVVzZJXohk) (laptop, phone, and nothing found). Built on `product-variants`. No backend change.

Decided by Al Mahamud:

| Decision | Detail |
|---|---|
| Search panel | A panel with the search, its filters, and search and reset buttons. |
| Table | Redesigned. |

Proposed by Claude. Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Search on the button | The list changes on খুঁজুন or Enter, not while typing. The চালু / আর্কাইভ switch changes it at once. |
| Filters | Category and tag dropdowns, which can now be combined. Chips below the panel show what's applied, each with an ×. |
| Reset | Clears the search, category and tag, and goes back to চালু. |
| Whole row | Anywhere on a row opens the product. |
| Left out | Sorting and a total count, which need backend work. |

The search stays in the address, so a refresh, the back button or a shared link keeps it. The other lists (orders, customers) keep their old search box that follows the typing.

**Different from the drawing.** The phone header stays white above the dark area, as on Today.

## Known limits

- **`PUT /api/v1/products/{id}/options`** is still there and still tested, but the app no longer calls it. On a product made from rows it would create the missing combinations. Remove it once nothing needs it.
- **Older products** with other options, such as "রং" and "সাইজ" made before this change, can't have those options renamed in the app any more. They can still add variants, up to the limit of 3 options on a product. There are no real sellers yet.
- **English** was never drawn for this page, but the text is in `en.json`.

## New text

All of it is in `frontend/src/i18n/bn.json` and `en.json` under `products.variantRows`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 1, 2026:** designed for laptop and phone, with the problems a form can show. The profit column was removed on review.
- **Nov 1, 2026:** built in the backend and the web app.
- **Nov 1, 2026:** product detail page designed and built.
- **Nov 1, 2026:** change stock dialog designed and built.
- **Nov 1, 2026:** products list designed, with search and reset buttons added on review, and built.
- **Nov 7, 2026:** generated SKUs of a product with a Bangla name keep one start: a variant added later reuses its product's code (P4PQZU-V4-S beside P4PQZU-V1-M), and a new product's code comes from its id rather than at random. Excel import recognises the app's own column names "বিক্রয় দাম" and "কম স্টকের সীমা", and either way of writing য়.
- **Nov 9, 2026:** a price range is written the same way on the products list and the product page: "৳১,২০০ – ৳১,৩০০". A photo that is too small is now explained as "১০০ × ১০০ পিক্সেল".
