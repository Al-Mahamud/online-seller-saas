import { createFileRoute } from "@tanstack/react-router";
import { requireRole } from "@/api/business";
import { CategoriesPage } from "@/features/catalog/CategoriesPage";

export const Route = createFileRoute("/_app/settings/categories")({
  beforeLoad: ({ context }) => requireRole(context.business, "MANAGER"),
  component: CategoriesPage,
});
