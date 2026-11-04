import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { DeliveryPage } from "@/features/delivery/DeliveryPage";

export const Route = createFileRoute("/_app/settings/delivery")({
  beforeLoad: ({ context }) => requirePermission(context.business, "SETTINGS"),
  component: DeliveryPage,
});
