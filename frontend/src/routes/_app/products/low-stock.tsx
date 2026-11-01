import { createFileRoute } from "@tanstack/react-router";
import { LowStockPage } from "@/features/catalog/LowStockPage";
import { pagingSearch } from "@/lib/paging";

export const Route = createFileRoute("/_app/products/low-stock")({
  validateSearch: pagingSearch,
  component: LowStockPage,
});
