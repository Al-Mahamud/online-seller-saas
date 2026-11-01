# Pagination

Nov 1, 2026 · **Status: built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

Design: [Mojud pagination design](https://claude.ai/artifact/FdDbjLtWm44oZBGLErsKSP).

Decided by Al Mahamud: numbered pages on every list, worked out by the backend, with code built to be reused.

## The contract

Every paged list takes the same query parameters and answers in the same shape:

```
GET /api/v1/orders?page=2&size=20

{ "items": [...], "page": 2, "size": 20, "totalItems": 153, "totalPages": 8 }
```

- `page` counts from 1. `size` is at most 100, and 20 when left out.
- An impossible number becomes the nearest sensible one. Text that isn't a number is a 400.
- A page past the end has no items and still the right totals, so a client can go back.
- `/notifications` adds `unread` to the same fields.

## Adding a paged list

**Backend** (`shared/paging`):

| Piece | Use |
|---|---|
| `PageQuery` | The request. Take it as `@ParameterObject PageQuery paging`, so it appears in the API contract. |
| `PagedResult.of(page, paging)` | From a Spring Data `Page`. Give the repository `paging.pageable()`. |
| `PagedResult.of(rows, paging, total)` | From plain SQL, with `limit`, `offset` and a count under the same filters. |
| `PagedResult.ofAll(all, paging)` | From a list that has to be worked out whole, such as low stock, sorted by a computed value. |
| `.map(...)`, `.mapItems(...)` | Turn rows into views. `mapItems` is for what's read in one query for the whole page. |

Always end the sort with the id, so rows with the same time can't swap between pages.

**Frontend**:

| Piece | Use |
|---|---|
| `pagedQuery(key, fetch)` (`lib/paging.ts`) | The query. Put the paging in the key. The page on screen stays until the next one arrives. |
| `pagingSearch` (`lib/paging.ts`) | The route's `validateSearch`, alone or spread in with the page's filters. |
| `AddressPagination` (`components/layout/Pagination.tsx`) | For a list that is the whole page: Back, reload and shared links come back to the same page. |
| `Pagination` with `useState<Paging>` | For a list inside a page or a dialog. Give `scrollTo` a ref to what should come into view. |
| `nextPage` with `LoadMore` | Only for a list that reads on as it scrolls. |

A filter that changes the list must also send `page: undefined`, so it starts from the first page.

## Which lists

| Numbered pages, place in the address | Numbered pages, place kept only while there | Not paged, and why |
|---|---|---|
| Orders | One customer's orders | COD parcels: a payout has to add up across all of a courier's parcels |
| Products | A variant's stock history | Add items modal: reads on as it scrolls, because ticks carry across products |
| Low stock | Invoices, on the billing page | Staff, categories, tags, delivery zones and plans: short lists |
| Customers | | Today's low-stock panel: shows 3, and asks the server for only those |
| Notifications | | |
| The activity log | | |
| Admin: businesses, invoices, activity | | |

## Proposed by Claude

Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Rows per page | 20, 50 or 100. The default is 20. |
| Laptop | The count, rows per page, আগের, page numbers and পরের. Long lists shorten to ১ … ১৪ ১৫ ১৬ … ৩১. |
| Phone | The count and rows per page, then আগের, "পৃষ্ঠা ২ / ৮" and পরের. |
| Short lists | Only "মোট ১২টি". An empty list keeps its own message and shows nothing more. |
| Filters | A new search, filter or status tab goes back to page 1 and keeps the rows per page. |
| Choosing orders | Ticks are for the page on screen and clear when the page changes, so nothing is packed or shipped from a page out of sight. |

## Different from the drawing

- **Where the pager sits:** in its own card below each list, not joined to the bottom of the table, so one component fits every list.

## Known limits

- **Rows can shift:** pages count from the newest, so a new order arriving while you're on page 2 can move one row across pages. The total stays right.
- **Bulk actions** can't span pages. Choosing a larger rows-per-page is the way to act on more at once.
- **Searches** now have pages, where they used to stop at the best 50.
- **Low stock** is still worked out whole on each request, then cut to a page, because its order depends on computed stock levels.
- **Local pages:** a customer's orders, stock history and invoices go back to the first page when you leave and return.
- **The `limit` and `cursor` parameters are gone** from these endpoints. The web app is the only client today.

## New text

In `frontend/src/i18n/bn.json` and `en.json` under `pagination`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 1, 2026:** designed and built, replacing "load more" and the cursor-based lists.
