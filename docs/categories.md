# Categories and tags (ক্যাটাগরি ও ট্যাগ)

`/settings/categories`, managers and owners. Design: https://claude.ai/artifact/4P9FCMvFLUXWXjDpN6bXH7
(local copy in `mojud-design-artifacts/21-categories-and-tags`).

## Layout

- **Dark header:** title, counts of categories, subcategories and tags, and one search box that
  narrows both lists (a match inside a category opens it).
- **Laptop:** categories (3 parts) and tags (2 parts) side by side. **Phone:** a switch shows one.
- **Categories:** add box; rows open and close; each shows its products (a top-level one counts its
  subcategories' too) as a link to the product list filtered by it; ↑ ↓ reorder (off while a search
  hides part of the group); a ⋯ menu to rename in place, add a subcategory (opens its box), move
  (a dialog of places) and delete. A category that isn't empty says why in the menu, and its
  dialog explains instead of asking. With none yet, one tap adds a common one.
- **Tags:** add box; chips with their product count; tapping one opens an editor to rename it,
  see its products, or delete it.

## Product counts

`CategoryNode.productCount` and `TagResponse.productCount`, counted on each request (no migration). A
category counts the products directly in it, archived ones too, since those also keep it from being
deleted; the page adds a top-level one's subcategories. The product list shows active products, so
a category with archived products shows a higher count than its filtered list.

## Download size

The ⋯ menu's library (Radix dropdown, about 18 KB compressed) loads on the first tap of a ⋯ button
(`CategoryMenu.tsx`, lazy, opening at once); `categoryMenuParts.ts` holds what the page shares with
it so the page doesn't pull the library in. That keeps the page within the 250 KB first-visit budget.
