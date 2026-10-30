import { createFileRoute } from "@tanstack/react-router";
import { CustomersPage } from "@/features/customers/CustomersPage";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/_app/customers/")({
  validateSearch: (search: Record<string, unknown>): { q?: string } => ({ q: searchText(search.q) }),
  component: CustomersPage,
});
