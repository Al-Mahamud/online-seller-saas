import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { ComingSoon } from "@/components/layout/ComingSoon";

export const Route = createFileRoute("/")({
  component: Today,
});

/** The dashboard (F7). */
function Today() {
  const { t } = useTranslation();
  return <ComingSoon title={t("nav.today")} />;
}
