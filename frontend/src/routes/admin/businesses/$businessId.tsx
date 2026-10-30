import { createFileRoute } from "@tanstack/react-router";
import { BusinessPage } from "@/features/admin/BusinessPage";

export const Route = createFileRoute("/admin/businesses/$businessId")({
  component: BusinessPage,
});
