import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { BillingPage } from "@/features/billing/BillingPage";

/** The upgrade dialog and the billing emails link here. */
export const Route = createFileRoute("/_app/billing/")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: BillingPage,
});
