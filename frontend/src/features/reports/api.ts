import { queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";

export type Today = components["schemas"]["DashboardToday"];
export type SalesRow = components["schemas"]["SalesReportsRow"];
export type GroupBy = "day" | "product" | "category";

export const dashboardQuery = (date: string | undefined) =>
  queryOptions({
    queryKey: ["dashboard", date],
    queryFn: () => call(api.GET("/api/v1/dashboard/today", { params: { query: { date } } })),
    meta: { showsOwnErrors: true },
    // A day out of reach fails the same way every time the seller comes back to the app.
    retry: false,
  });

export const salesQuery = (from: string, to: string, groupBy: GroupBy) =>
  queryOptions({
    queryKey: ["sales", from, to, groupBy],
    queryFn: () => call(api.GET("/api/v1/reports/sales", { params: { query: { from, to, groupBy } } })),
    meta: { showsOwnErrors: true },
    retry: false,
  });

export const profitQuery = (from: string, to: string) =>
  queryOptions({
    queryKey: ["profit", from, to],
    queryFn: () => call(api.GET("/api/v1/reports/profit", { params: { query: { from, to } } })),
    meta: { showsOwnErrors: true },
    retry: false,
  });

/** Saves what the server sends as a file. Through the API client, so a refusal (the plan, say) is an error like any other. */
async function save(request: Promise<{ data?: unknown; error?: unknown; response: Response }>, name: string) {
  const blob = (await call(request)) as Blob;
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

export type Download = "orders" | "products" | "customers" | "everything";

export function download(what: Download, from: string, to: string) {
  switch (what) {
    case "orders":
      return save(
        api.GET("/api/v1/exports/orders.csv", { params: { query: { from, to } }, parseAs: "blob" }),
        `orders-${from}-${to}.csv`,
      );
    case "products":
      return save(api.GET("/api/v1/exports/products.csv", { parseAs: "blob" }), "products.csv");
    case "customers":
      return save(api.GET("/api/v1/exports/customers.csv", { parseAs: "blob" }), "customers.csv");
    case "everything":
      return save(api.GET("/api/v1/business/export", { parseAs: "blob" }), "mojud-export.zip");
  }
}
