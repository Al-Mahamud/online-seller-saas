import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { BillingPage } from "@/features/billing/BillingPage";

/** The upgrade dialog and the billing emails link here. */
export const Route = createFileRoute("/_app/billing/")({
  beforeLoad: ({ context }) => requirePermission(context.business, "SETTINGS"),
  component: BillingPage,
});
