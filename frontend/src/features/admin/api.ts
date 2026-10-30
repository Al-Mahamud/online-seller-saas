import { queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";

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

export const businessesQuery = (q: string | undefined) =>
  queryOptions({
    queryKey: ["admin", "businesses", q],
    queryFn: () => call(api.GET("/api/v1/admin/businesses", { params: { query: { q } } })),
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

export const openInvoicesQuery = queryOptions({
  queryKey: ["admin", "invoices"],
  queryFn: () => call(api.GET("/api/v1/admin/billing/invoices", { params: { query: { status: "OPEN" } } })),
});

export const auditQuery = queryOptions({
  queryKey: ["admin", "audit"],
  queryFn: () => call(api.GET("/api/v1/admin/audit")),
});

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
