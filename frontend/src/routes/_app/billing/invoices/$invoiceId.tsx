import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { InvoicePage } from "@/features/billing/InvoicePage";

export const Route = createFileRoute("/_app/billing/invoices/$invoiceId")({
  beforeLoad: ({ context }) => requirePermission(context.business, "SETTINGS"),
  component: InvoicePage,
});
