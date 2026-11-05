import { createFileRoute } from "@tanstack/react-router";
import type { BusinessFilters } from "@/features/admin/api";
import { BusinessesPage } from "@/features/admin/BusinessesPage";
import { BUSINESS_STATES } from "@/features/admin/search";
import { pagingSearch } from "@/lib/paging";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/admin/businesses/")({
  validateSearch: (search: Record<string, unknown>): BusinessFilters => ({
    q: searchText(search.q),
    plan: typeof search.plan === "string" && /^[A-Z_]{1,30}$/.test(search.plan) ? search.plan : undefined,
    state: BUSINESS_STATES.find((state) => state === search.state),
    ...pagingSearch(search),
  }),
  component: BusinessesPage,
});
