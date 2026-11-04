import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { EditOrderPage } from "@/features/orders/EditOrderPage";

export const Route = createFileRoute("/_app/orders/$orderId/edit")({
  beforeLoad: ({ context }) => requirePermission(context.business, "ORDERS"),
  component: EditOrderPage,
});
