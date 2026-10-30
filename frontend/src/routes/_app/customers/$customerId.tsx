import { createFileRoute } from "@tanstack/react-router";
import { CustomerPage } from "@/features/customers/CustomerPage";

export const Route = createFileRoute("/_app/customers/$customerId")({
  component: CustomerPage,
});
