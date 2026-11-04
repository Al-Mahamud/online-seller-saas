import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { AuditLogPage, activityFilters } from "@/features/staff/AuditLogPage";
import { loadPageTexts } from "@/i18n/pages";

/** The filters stay in the address, so a refresh or a shared link keeps them. */
export const Route = createFileRoute("/_app/settings/audit-log")({
  validateSearch: activityFilters,
  beforeLoad: ({ context }) => requireRole(context.business, "OWNER"),
  loader: () => loadPageTexts("activity", "team"),
  component: AuditLogPage,
});
