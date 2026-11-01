import { createFileRoute } from "@tanstack/react-router";
import type { ProductFilters } from "@/features/catalog/api";
import { ProductListPage } from "@/features/catalog/ProductListPage";
import { pagingSearch } from "@/lib/paging";
import { searchText } from "@/lib/search";

/** Filters stay in the address, so a refresh or a shared link keeps them. */
function validateSearch(search: Record<string, unknown>): ProductFilters {
  const text = (value: unknown) => (typeof value === "string" && value ? value : undefined);
  return {
    q: searchText(search.q),
    categoryId: text(search.categoryId),
    tagId: text(search.tagId),
    status: search.status === "ARCHIVED" ? "ARCHIVED" : undefined,
    ...pagingSearch(search),
  };
}

export const Route = createFileRoute("/_app/products/")({
  validateSearch,
  component: ProductListPage,
});
