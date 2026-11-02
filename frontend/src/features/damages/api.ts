import { type QueryClient, queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { pagedQuery } from "@/lib/paging";
import type { DamageFilters } from "./search";

export type Damage = components["schemas"]["DamageReportsDamage"];
export type DamageSummary = components["schemas"]["DamageReportsSummary"];
export type DamageReason = NonNullable<Damage["reason"]>;
export type DamageSource = Damage["source"];

/** In the order the seller picks from. */
export const DAMAGE_REASONS: DamageReason[] = ["BROKEN", "STAIN", "LOST", "EXPIRED", "OTHER"];

/** The days and search the page shows; the dates are always filled in, the server's defaults made plain. */
export interface DamageRange {
  from: string;
  to: string;
  q?: string;
}

export const damagesQuery = (range: DamageRange, filters: Pick<DamageFilters, "source" | "page" | "size">) =>
  pagedQuery(["damages", "list", range, filters], () =>
    call(api.GET("/api/v1/damages", { params: { query: { ...range, ...filters } } })),
  );

export const damageSummaryQuery = (range: DamageRange) =>
  queryOptions({
    queryKey: ["damages", "summary", range],
    queryFn: () => call(api.GET("/api/v1/damages/summary", { params: { query: range } })),
    // A plan that doesn't reach the days asks for an upgrade once, from the list.
    meta: { optional: true },
    retry: false,
  });

/** Damage moves stock: whatever shows either reads again. */
export function damagesChanged(queryClient: QueryClient) {
  for (const key of ["damages", "product", "products", "low-stock", "movements"]) {
    void queryClient.invalidateQueries({ queryKey: [key] });
  }
}
