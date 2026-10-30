import { createFileRoute } from "@tanstack/react-router";
import { InvoicesPage } from "@/features/admin/InvoicesPage";

export const Route = createFileRoute("/admin/invoices")({
  component: InvoicesPage,
});
