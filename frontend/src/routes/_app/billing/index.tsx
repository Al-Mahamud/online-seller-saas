import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { BillingPage } from "@/features/billing/BillingPage";
import { loadPageTexts } from "@/i18n/pages";

/** The upgrade dialog and the billing emails link here. The pay dialog's wallet texts load with it. */
export const Route = createFileRoute("/_app/billing/")({
  beforeLoad: ({ context }) => requirePermission(context.business, "SETTINGS"),
  loader: () => loadPageTexts("pay"),
  component: BillingPage,
});
