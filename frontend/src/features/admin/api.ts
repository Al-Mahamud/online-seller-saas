import { queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { type Paging, pagedQuery } from "@/lib/paging";
import { AUDIT_KINDS, type AuditKind, type BusinessState } from "./search";

export { AUDIT_KINDS, type AuditKind, type BusinessState };

export type AdminBusiness = components["schemas"]["AdminBusinessDetail"];
export type AdminPlan = components["schemas"]["BillingAdministrationPlanInfo"];
export type AdminInvoice = components["schemas"]["BillingAdminAdminInvoice"];
export type AuditEntry = components["schemas"]["AdminAuditEntry"];

/** Also what tells the layout whether this session may be here at all. */
export const metricsQuery = queryOptions({
  queryKey: ["admin", "metrics"],
  queryFn: () => call(api.GET("/api/v1/admin/metrics")),
  retry: false,
  meta: { showsOwnErrors: true },
});

export interface BusinessFilters extends Paging {
  q?: string;
  plan?: string;
  state?: BusinessState;
}

export const businessesQuery = ({ q, plan, state, ...paging }: BusinessFilters) =>
  pagedQuery(["admin", "businesses", q, plan, state, paging], () =>
    call(api.GET("/api/v1/admin/businesses", { params: { query: { q, plan, state, ...paging } } })),
  );

/** The few newest, for the overview. */
export const newestBusinessesQuery = queryOptions({
  queryKey: ["admin", "businesses", "newest"],
  queryFn: () => call(api.GET("/api/v1/admin/businesses", { params: { query: { size: 5 } } })),
});

export const businessQuery = (id: string) =>
  queryOptions({
    queryKey: ["admin", "business", id],
    queryFn: () => call(api.GET("/api/v1/admin/businesses/{id}", { params: { path: { id } } })),
  });

export const plansQuery = queryOptions({
  queryKey: ["admin", "plans"],
  queryFn: () => call(api.GET("/api/v1/admin/plans")),
});

/** Bank transfers still to be seen arriving ({@code OPEN}), or already marked paid. */
export const transfersQuery = (status: "OPEN" | "PAID", paging: Paging) =>
  pagedQuery(["admin", "invoices", status, paging], () =>
    call(
      api.GET("/api/v1/admin/billing/invoices", {
        params: { query: { status, payBy: "BANK_TRANSFER", ...paging } },
      }),
    ),
  );

export const auditQuery = (kind: AuditKind | undefined, paging: Paging) =>
  pagedQuery(["admin", "audit", kind, paging], () =>
    call(
      api.GET("/api/v1/admin/audit", {
        params: { query: { action: kind ? [...AUDIT_KINDS[kind]] : undefined, ...paging } },
      }),
    ),
  );

/** Saved through the API client, so a refusal is an error like any other. */
export async function downloadLedger(month: string) {
  const blob = (await call(
    api.GET("/api/v1/admin/billing/ledger.csv", { params: { query: { month } }, parseAs: "blob" }),
  )) as unknown as Blob;
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `ledger-${month}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}
