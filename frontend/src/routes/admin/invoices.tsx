import { createFileRoute } from "@tanstack/react-router";
import { InvoicesPage } from "@/features/admin/InvoicesPage";
import { type Paging, pagingSearch } from "@/lib/paging";

export const Route = createFileRoute("/admin/invoices")({
  validateSearch: (search: Record<string, unknown>): { tab?: "paid" } & Paging => ({
    tab: search.tab === "paid" ? "paid" : undefined,
    ...pagingSearch(search),
  }),
  component: InvoicesPage,
});
