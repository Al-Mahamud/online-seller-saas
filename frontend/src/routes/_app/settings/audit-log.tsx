import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { AuditLogPage } from "@/features/staff/AuditLogPage";

export const Route = createFileRoute("/_app/settings/audit-log")({
  beforeLoad: ({ context }) => requireRole(context.business, "OWNER"),
  component: AuditLogPage,
});
