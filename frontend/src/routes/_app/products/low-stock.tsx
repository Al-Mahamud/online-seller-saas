import { createFileRoute } from "@tanstack/react-router";
import { LowStockPage } from "@/features/catalog/LowStockPage";

export const Route = createFileRoute("/_app/products/low-stock")({
  component: LowStockPage,
});
