import { createFileRoute } from "@tanstack/react-router";
import { CodPage } from "@/features/orders/CodPage";

export const Route = createFileRoute("/_app/cod/")({
  component: CodPage,
});
