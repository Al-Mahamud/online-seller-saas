import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { NewProductPage } from "@/features/catalog/NewProductPage";

export const Route = createFileRoute("/_app/products/new")({
  beforeLoad: ({ context }) => requirePermission(context.business, "PRODUCTS"),
  component: NewProductPage,
});
