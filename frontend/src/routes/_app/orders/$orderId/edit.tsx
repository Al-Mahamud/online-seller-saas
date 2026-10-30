import { createFileRoute } from "@tanstack/react-router";
import { EditOrderPage } from "@/features/orders/EditOrderPage";

export const Route = createFileRoute("/_app/orders/$orderId/edit")({
  component: EditOrderPage,
});
