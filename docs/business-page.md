# Business page (ব্যবসা)

`/settings/business`, managers and owners. Design: https://claude.ai/artifact/DfisVxFXbiMXqy9YeZ8tZ4
(local copy in `mojud-design-artifacts/20-business-page`).

## Layout

- **Dark header:** logo (or the name's initials) with a camera button, the name as the page's h1 (live
  while typing), role, plan chip (→ billing), phone · address, and four figures: when stock goes
  down, the low-stock limit, the next order number, members.
- **Laptop:** a side list of the parts, following the scroll. **Phone:** a row of chips.
- **Cards:** business details and logo; orders and stock (the fixed stock rule with a new → confirmed →
  with courier strip, and the low-stock limit); order number and slip (live slip preview); search colour; deleting.

## Saving

One form for everything. Changing anything shows a sticky bar ("Nটি পরিবর্তন সংরক্ষণ হয়নি",
বাতিল / সংরক্ষণ করুন). Saving sends only what changed: `PATCH /business` for name/phone/address,
`PATCH /business/settings` for the low-stock limit, prefix and footer, `PUT /business/search-theme` (owner only).
The logo still saves on picking a file.

## Where the figures come from

- Next order number: the newest order's `number` + 1 (the list is newest first), else 1001; the
  prefix as typed, `#` when empty, like the server.
- Members: `GET /api/v1/staff`. Plan: `GET /api/v1/billing`.
- The delete dialog counts products, orders and customers from their lists' totals.

## Who sees what

- Managers: everything except deleting; the colour shows the current choice, locked.
- Staff: not this page.

## Stock goes down at the courier (fixed for now, Nov 2026)

The page no longer offers "when stock goes down": it shows the rule, stock set aside when an order is
confirmed and taken when it is handed to the courier, with "আপাতত সব ব্যবসায় এটিই নিয়ম".
`V37__stock_down_at_courier.sql` moved every business on CONFIRM to SHIP. Open orders are safe: each
records what it did to stock (`orders.stock_state`), so one confirmed under CONFIRM stays DEDUCTED and
shipping doesn't take it again.

The server still accepts `stockCommitPoint` on `PATCH /business/settings` and keeps the CONFIRM code,
so the option can come back by putting the choice on the page again. To lock it on the server too,
make the settings endpoint refuse CONFIRM (the backend tests that switch to CONFIRM would then set it
another way).
