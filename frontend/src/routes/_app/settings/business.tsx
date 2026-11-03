import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { BusinessPage } from "@/features/business/BusinessPage";

export const Route = createFileRoute("/_app/settings/business")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: BusinessPage,
});
