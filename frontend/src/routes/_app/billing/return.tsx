import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { PaymentReturnPage } from "@/features/billing/PaymentReturnPage";

export const Route = createFileRoute("/_app/billing/return")({
  validateSearch: (search: Record<string, unknown>): { result?: string; invoice?: string } => ({
    result: typeof search.result === "string" ? search.result : undefined,
    invoice: typeof search.invoice === "string" ? search.invoice : undefined,
  }),
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: PaymentReturnPage,
});
