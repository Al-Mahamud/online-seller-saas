import { createFileRoute } from "@tanstack/react-router";
import { TodayPage } from "@/features/reports/TodayPage";
import { dayText } from "@/lib/dates";

export const Route = createFileRoute("/_app/")({
  validateSearch: (search: Record<string, unknown>): { date?: string } => ({ date: dayText(search.date) }),
  component: TodayPage,
});
