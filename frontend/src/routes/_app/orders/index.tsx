import { createFileRoute } from "@tanstack/react-router";
import { OrdersPage } from "@/features/orders/OrdersPage";
import { orderFilters } from "@/features/orders/search";

export const Route = createFileRoute("/_app/orders/")({
  validateSearch: orderFilters,
  component: OrdersPage,
});
