import { createFileRoute } from "@tanstack/react-router";
import { BusinessesPage } from "@/features/admin/BusinessesPage";
import { type Paging, pagingSearch } from "@/lib/paging";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/admin/")({
  validateSearch: (search: Record<string, unknown>): { q?: string } & Paging => ({
    q: searchText(search.q),
    ...pagingSearch(search),
  }),
  component: BusinessesPage,
});
