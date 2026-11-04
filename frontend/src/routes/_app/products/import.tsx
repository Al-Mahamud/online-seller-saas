import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { ImportPage } from "@/features/catalog/ImportPage";

/** The import in progress stays in the address, so a refresh comes back to it. */
export const Route = createFileRoute("/_app/products/import")({
  validateSearch: (search: Record<string, unknown>): { job?: string } =>
    typeof search.job === "string" ? { job: search.job } : {},
  beforeLoad: ({ context }) => requirePermission(context.business, "PRODUCTS"),
  component: ImportPage,
});
