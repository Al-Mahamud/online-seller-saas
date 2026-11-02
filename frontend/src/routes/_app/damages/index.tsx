import { createFileRoute } from "@tanstack/react-router";
import { DamagesPage } from "@/features/damages/DamagesPage";
import { damageFilters } from "@/features/damages/search";

export const Route = createFileRoute("/_app/damages/")({
  validateSearch: damageFilters,
  component: DamagesPage,
});
