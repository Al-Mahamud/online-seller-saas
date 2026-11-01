import { createFileRoute } from "@tanstack/react-router";
import { InvoicesPage } from "@/features/admin/InvoicesPage";
import { pagingSearch } from "@/lib/paging";

export const Route = createFileRoute("/admin/invoices")({
  validateSearch: pagingSearch,
  component: InvoicesPage,
});
