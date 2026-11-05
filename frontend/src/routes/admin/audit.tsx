import { createFileRoute } from "@tanstack/react-router";
import { AuditPage } from "@/features/admin/AuditPage";
import { AUDIT_KINDS, type AuditKind } from "@/features/admin/search";
import { type Paging, pagingSearch } from "@/lib/paging";

export const Route = createFileRoute("/admin/audit")({
  validateSearch: (search: Record<string, unknown>): { kind?: AuditKind } & Paging => ({
    kind: (Object.keys(AUDIT_KINDS) as AuditKind[]).find((kind) => kind === search.kind),
    ...pagingSearch(search),
  }),
  component: AuditPage,
});
