import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { ComingSoon } from "@/components/layout/ComingSoon";

export const Route = createFileRoute("/_app/billing/")({
  component: Billing,
});

/** Plans and payment (F8). The upgrade dialog and the billing emails link here. */
function Billing() {
  const { t } = useTranslation();
  return <ComingSoon title={t("billing.title")} />;
}
