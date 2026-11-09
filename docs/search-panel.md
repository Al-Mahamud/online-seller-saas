# Search panel

Built on Nov 2, 2026, at Al Mahamud's request, from the design at https://claude.ai/artifact/3CgbjvZL1RxjYFzvtb77cy (copy in `mojud-design-artifacts/17-search-panel`). One panel for every list: Orders, Products, Customers, COD and Damaged goods.

## What it does

| Part | What's in it |
|---|---|
| Bar | The text box, the page's filters as buttons, and `খুঁজুন`. Text waits for the button or Enter; a filter picked applies at once. `/` anywhere on the page goes to the box. |
| Dates | Quick picks (today, yesterday, last 7 and 30 days, this month, last month) and two dates, in a small panel. |
| Choices | Products: category, tag, and on sale or archived. |
| Under the bar | What's being searched by, each chip with ×, then `রিসেট`; on the right, how many were found (Products) and the running-low link. |
| Recent searches | The last four of each list, in this browser only, under an empty box. Arrow keys move through them. |
| Phones | The box with the search button inside, and a filter button showing how many filters are on; it opens a sheet with every filter, `রিসেট` and `ফলাফল দেখুন`. |

## Colours

Two looks, chosen by the owner for the whole business in Business settings (`খোঁজার প্যানেলের রং`): **Ledger** (dark green, marigold button, the default) and **Mint** (soft mint, dark green button). Others see the choice; only the owner changes it (`PUT /api/v1/business/search-theme`, `searchTheme` in the settings, migration `V35`).

Inside a dark header (phones, COD, Damaged goods) the ledger look has no ground of its own and the mint one is a card.

## Different from the drawing

- **Tips** under recent searches ("what you can search by") were left out: each box's hint already says it.
- **Status** on Products is a third filter button (on sale or archived), not a separate switch.
- **Orders' status tabs** stay where they were, under the panel.
- **Colours:** only A and C of the ten drawn, as asked.

## Known limits

- **Recent searches** live in this browser only, so they don't follow a person to another phone, and a private window has none.
- **Reports** keep their own day picker; it isn't a search.
- **Bangla text** needs Al Mahamud's read-through (`docs/bangla-review.csv`).

## Code

`components/search/ListSearch.tsx` (the panel, its date and choice buttons, and the phone sheet), `components/search/theme.ts` (the two looks and `useSearchTheme`), `components/search/dates.ts` (quick picks), `lib/recentSearches.ts`, `components/ui/popover.tsx`. Business settings: `features/business/SearchColourSection.tsx`. Browser tests use `e2e/support/search.ts` (`pickDays`, `pickChoice`).

## Change log

- **Nov 2, 2026:** designed and built, with the ledger and mint looks.
- **Nov 9, 2026:** search finds য়, ড় and ঢ় typed either way. Some keyboards type them as one character and others as two. Names are compared lower-case and Unicode-normalised (NFC): on the server by the `search_text` database function (migration V40), in the page by `containsText` in `src/lib/search.ts`.
