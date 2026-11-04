import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { CategoriesPage } from "@/features/catalog/CategoriesPage";

export const Route = createFileRoute("/_app/settings/categories")({
  beforeLoad: ({ context }) => requirePermission(context.business, "PRODUCTS"),
  component: CategoriesPage,
});
