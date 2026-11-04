import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { CustomersPage } from "@/features/customers/CustomersPage";
import { type Paging, pagingSearch } from "@/lib/paging";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/_app/customers/")({
  beforeLoad: ({ context }) => requirePermission(context.business, "ORDERS", "CUSTOMERS"),
  validateSearch: (search: Record<string, unknown>): { q?: string } & Paging => ({
    q: searchText(search.q),
    ...pagingSearch(search),
  }),
  component: CustomersPage,
});
