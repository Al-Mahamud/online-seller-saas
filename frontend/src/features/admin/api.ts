import { queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { type Paging, pagedQuery } from "@/lib/paging";

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

export const businessesQuery = (q: string | undefined, paging: Paging) =>
  pagedQuery(["admin", "businesses", q, paging], () =>
    call(api.GET("/api/v1/admin/businesses", { params: { query: { q, ...paging } } })),
  );

export const businessQuery = (id: string) =>
  queryOptions({
    queryKey: ["admin", "business", id],
    queryFn: () => call(api.GET("/api/v1/admin/businesses/{id}", { params: { path: { id } } })),
  });

export const plansQuery = queryOptions({
  queryKey: ["admin", "plans"],
  queryFn: () => call(api.GET("/api/v1/admin/plans")),
});

/** Bank transfers still to be seen arriving. */
export const openInvoicesQuery = (paging: Paging) =>
  pagedQuery(["admin", "invoices", paging], () =>
    call(
      api.GET("/api/v1/admin/billing/invoices", {
        params: { query: { status: "OPEN", payBy: "BANK_TRANSFER", ...paging } },
      }),
    ),
  );

export const auditQuery = (paging: Paging) =>
  pagedQuery(["admin", "audit", paging], () => call(api.GET("/api/v1/admin/audit", { params: { query: paging } })));

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
