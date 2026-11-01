import { createFileRoute } from "@tanstack/react-router";
import { CodPage } from "@/features/orders/CodPage";
import { codFilters } from "@/features/orders/search";

export const Route = createFileRoute("/_app/cod/")({
  validateSearch: codFilters,
  component: CodPage,
});
