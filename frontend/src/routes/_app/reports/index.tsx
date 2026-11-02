import { createFileRoute } from "@tanstack/react-router";
import type { GroupBy } from "@/features/reports/api";
import { ReportsPage } from "@/features/reports/ReportsPage";
import { dayText } from "@/lib/dates";

export const Route = createFileRoute("/_app/reports/")({
  validateSearch: (
    search: Record<string, unknown>,
  ): { from?: string; to?: string; by?: GroupBy; share?: "category" } => ({
    from: dayText(search.from),
    to: dayText(search.to),
    by: search.by === "product" || search.by === "category" ? search.by : undefined,
    share: search.share === "category" ? "category" : undefined,
  }),
  component: ReportsPage,
});
