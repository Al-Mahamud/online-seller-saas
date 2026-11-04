import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { CodPage } from "@/features/orders/CodPage";
import { codFilters } from "@/features/orders/search";

export const Route = createFileRoute("/_app/cod/")({
  beforeLoad: ({ context }) => requirePermission(context.business, "ORDERS"),
  validateSearch: codFilters,
  component: CodPage,
});
