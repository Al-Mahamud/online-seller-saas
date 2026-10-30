import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { InvoicePage } from "@/features/billing/InvoicePage";

export const Route = createFileRoute("/_app/billing/invoices/$invoiceId")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: InvoicePage,
});
