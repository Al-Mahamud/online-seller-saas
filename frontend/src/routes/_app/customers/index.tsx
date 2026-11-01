import { createFileRoute } from "@tanstack/react-router";
import { CustomersPage } from "@/features/customers/CustomersPage";
import { type Paging, pagingSearch } from "@/lib/paging";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/_app/customers/")({
  validateSearch: (search: Record<string, unknown>): { q?: string } & Paging => ({
    q: searchText(search.q),
    ...pagingSearch(search),
  }),
  component: CustomersPage,
});
