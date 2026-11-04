import { createFileRoute } from "@tanstack/react-router";
import { requirePermission } from "@/api/business";
import { DamagesPage } from "@/features/damages/DamagesPage";
import { damageFilters } from "@/features/damages/search";

export const Route = createFileRoute("/_app/damages/")({
  beforeLoad: ({ context }) => requirePermission(context.business, "STOCK"),
  validateSearch: damageFilters,
  component: DamagesPage,
});
