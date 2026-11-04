import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { NewOrderPage } from "@/features/orders/NewOrderPage";
import { searchText } from "@/lib/search";

export const Route = createFileRoute("/_app/orders/new")({
  beforeLoad: ({ context }) => requirePermission(context.business, "ORDERS"),
  validateSearch: (search: Record<string, unknown>): { phone?: string } => ({ phone: searchText(search.phone) }),
  component: NewOrderPage,
});
