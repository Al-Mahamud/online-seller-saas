import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { OrderPage } from "@/features/orders/OrderPage";

export const Route = createFileRoute("/_app/orders/$orderId/")({
  beforeLoad: ({ context }) => requirePermission(context.business, "ORDERS"),
  component: OrderPage,
});
