# New product page redesign

Nov 1, 2026 · **Status: built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

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

## Known limits

- **`PUT /api/v1/products/{id}/options`** is still there and still tested, but the app no longer calls it. On a product made from rows it would create the missing combinations. Remove it once nothing needs it.
- **Older products** with other options, such as "রং" and "সাইজ" made before this change, can't have those options renamed in the app any more. They can still add variants, up to the limit of 3 options on a product. There are no real sellers yet.
- **English** was never drawn for this page, but the text is in `en.json`.

## New text

All of it is in `frontend/src/i18n/bn.json` and `en.json` under `products.variantRows`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 1, 2026:** designed for laptop and phone, with the problems a form can show. The profit column was removed on review.
- **Nov 1, 2026:** built in the backend and the web app.
