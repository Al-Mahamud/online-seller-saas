import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { StaffPage } from "@/features/staff/StaffPage";

export const Route = createFileRoute("/_app/settings/staff")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: StaffPage,
});
