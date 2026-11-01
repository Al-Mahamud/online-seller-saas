# Sidebar and profile redesign

Nov 1, 2026 · **Status: built on branch `product-variants`. Not yet tried on a real server, on a real phone or with sellers.** Add later changes to this document's change log.

Design: [Mojud sidebar and profile design](https://claude.ai/artifact/488gzNHXYA8kzd7QQFAwLz). No backend change.

## Decided by Al Mahamud

- What's under আরও goes in the sidebar.
- Account details go inside a profile.
- Clicking the business name at the top left shows a profile option.

## Proposed by Claude

Change any of these by saying so:

| Proposal | Detail |
|---|---|
| Dark sidebar | Dark green, with icons, and the current page highlighted. |
| Two groups | আজ, অর্ডার, পণ্য, গ্রাহক, COD হিসাব and রিপোর্ট, then a "ব্যবস্থাপনা" group. Who sees what follows the same rules as before. |
| Business menu | Your name and contact, প্রোফাইল, ব্যবসা বদলান, নতুন ব্যবসা, লগ আউট. |
| Profile page | "অ্যাকাউন্ট" is now "প্রোফাইল": a dark band with your name, user ID and log out, and tabs for তথ্য, নিরাপত্তা and ইমেইলের পছন্দ. |
| Phones | No sidebar, so the আরও tab stays, without the account items. Tapping the business name opens the same menu. |
| অ্যাডমিন and app install | In the business menu, when they apply. |
| ব্যবসা ছেড়ে দিন | At the end of the profile's তথ্য tab, for anyone who isn't the owner. |
| Back links | Pages under আরও show their back link on phones only. |
| No permission | A page the role doesn't reach now goes to আজ on every screen, not to আরও. `/more` on a laptop also goes to আজ. |

## Different from the drawing

- **Language on a laptop:** in its own card under আপনার তথ্য, not inside it.
- **Contact card's title:** keeps the existing "ফোন ও ইমেইল", not "যোগাযোগ".

## Known limits

- **ফোনে অ্যাপ হিসেবে রাখুন** is less easy to find inside the menu than it was at the top of আরও.
- **English** was never drawn, but the text is in `en.json`.

## New text

In `frontend/src/i18n/bn.json` and `en.json`: `nav.manage`, `nav.work`, `account.title`, `account.profile`, `account.info`, `account.identity` and `account.parts`, and in `docs/bangla-review.csv`. The Bangla needs Al Mahamud's read-through.

## Change log

- **Nov 1, 2026:** designed and built.
