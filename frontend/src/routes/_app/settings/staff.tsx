import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { StaffPage } from "@/features/staff/StaffPage";
import { loadPageTexts } from "@/i18n/pages";

export const Route = createFileRoute("/_app/settings/staff")({
  beforeLoad: ({ context }) => requirePermission(context.business, "TEAM"),
  loader: () => loadPageTexts("team"),
  component: StaffPage,
});
