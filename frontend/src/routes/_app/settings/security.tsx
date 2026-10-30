import { createFileRoute } from "@tanstack/react-router";
import { SecurityPage } from "@/features/account/SecurityPage";

export const Route = createFileRoute("/_app/settings/security")({
  component: SecurityPage,
});
