import { createFileRoute } from "@tanstack/react-router";
import { AuditPage } from "@/features/admin/AuditPage";
import { pagingSearch } from "@/lib/paging";

export const Route = createFileRoute("/admin/audit")({
  validateSearch: pagingSearch,
  component: AuditPage,
});
