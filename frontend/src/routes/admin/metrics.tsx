import { createFileRoute } from "@tanstack/react-router";
import { MetricsPage } from "@/features/admin/MetricsPage";

export const Route = createFileRoute("/admin/metrics")({
  component: MetricsPage,
});
