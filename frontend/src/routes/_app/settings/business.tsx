import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { BusinessPage } from "@/features/business/BusinessPage";

export const Route = createFileRoute("/_app/settings/business")({
  beforeLoad: ({ context }) => requirePermission(context.business, "SETTINGS"),
  component: BusinessPage,
});
