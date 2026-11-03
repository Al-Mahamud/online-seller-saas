# Delivery charges (ডেলিভারি চার্জ)

`/settings/delivery`, managers and owners. Design: https://claude.ai/artifact/1gnvjdhoGKViYzY83SXnwp
(local copy in `mojud-design-artifacts/22-delivery-charges`). No server change: the zones are still
saved together with `PUT /api/v1/delivery-zones`.

## Layout

- **Dark header:** title, and the three steps a charge is chosen by: thana or upazila, then district,
  then everywhere else.
- **Zone cards:** numbered and tinted; name; charge with − / + (৳10 steps); delete. Their places as
  chips marked জেলা or থানা, a count, and "জেলা বা থানা যোগ করুন". A zone with no places warns that
  its charge never applies.
- **Everywhere else:** its own cream card, always last: a name and a charge only. Which zone is the
  default can no longer be switched on this page (it would drop that zone's places).
- **Check:** district and thana selects ask the server's own quote (the order form's) and say why
  (`matchedBy`); "এক নজরে" lists every saved zone's charge. Beside the zones on laptop, above on phone.
  It checks the saved rules, and says so while there are unsaved changes.
- **Save bar:** like the business page; on save it marks a missing or repeated name and a bad charge
  on the field, and counts them in the bar.

## Adding places (`PlacesDialog.tsx`, loaded when opened)

Districts grouped by division, or one district's thanas; search in Bangla or English; tick many. A
place already here is ticked and greyed; one in another zone says which, and picking it moves it
here (a place is in one zone at most). A move shows under the card with "ফিরিয়ে দিন" until saved.
