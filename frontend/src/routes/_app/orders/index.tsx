import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { OrdersPage } from "@/features/orders/OrdersPage";
import { orderFilters } from "@/features/orders/search";

export const Route = createFileRoute("/_app/orders/")({
  beforeLoad: ({ context }) => requirePermission(context.business, "ORDERS"),
  validateSearch: orderFilters,
  component: OrdersPage,
});
