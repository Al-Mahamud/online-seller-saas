import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { ComingSoon } from "@/components/layout/ComingSoon";

export const Route = createFileRoute("/more")({
  component: More,
});

/** Customers, staff, settings, billing and the rest (F2 onwards). */
function More() {
  const { t } = useTranslation();
  return <ComingSoon title={t("nav.more")} />;
}
