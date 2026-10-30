import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { NewProductPage } from "@/features/catalog/NewProductPage";

export const Route = createFileRoute("/_app/products/new")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: NewProductPage,
});
