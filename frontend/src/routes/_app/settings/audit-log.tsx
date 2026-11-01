import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { AuditLogPage } from "@/features/staff/AuditLogPage";
import { pagingSearch } from "@/lib/paging";

export const Route = createFileRoute("/_app/settings/audit-log")({
  validateSearch: pagingSearch,
  beforeLoad: ({ context }) => requireRole(context.business, "OWNER"),
  component: AuditLogPage,
});
