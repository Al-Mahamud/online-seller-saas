import { createFileRoute } from "@tanstack/react-router";
import { SecurityPage } from "@/features/account/SecurityPage";
import { loadPageTexts } from "@/i18n/pages";

export const Route = createFileRoute("/_app/settings/security")({
  loader: () => loadPageTexts("twoFactor"),
  component: SecurityPage,
});
