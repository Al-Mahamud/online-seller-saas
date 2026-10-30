import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { DeliveryPage } from "@/features/delivery/DeliveryPage";

export const Route = createFileRoute("/_app/settings/delivery")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: DeliveryPage,
});
