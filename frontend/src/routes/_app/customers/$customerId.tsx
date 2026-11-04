import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { CustomerPage } from "@/features/customers/CustomerPage";

export const Route = createFileRoute("/_app/customers/$customerId")({
  beforeLoad: ({ context }) => requirePermission(context.business, "ORDERS", "CUSTOMERS"),
  component: CustomerPage,
});
