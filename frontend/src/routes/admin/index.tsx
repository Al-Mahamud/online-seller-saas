import { createFileRoute } from "@tanstack/react-router";
import { BusinessesPage } from "@/features/admin/BusinessesPage";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/admin/")({
  validateSearch: (search: Record<string, unknown>): { q?: string } => ({ q: searchText(search.q) }),
  component: BusinessesPage,
});
