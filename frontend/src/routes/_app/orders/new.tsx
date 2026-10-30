import { createFileRoute } from "@tanstack/react-router";
import { NewOrderPage } from "@/features/orders/NewOrderPage";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/_app/orders/new")({
  validateSearch: (search: Record<string, unknown>): { phone?: string } => ({ phone: searchText(search.phone) }),
  component: NewOrderPage,
});
