import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { ComingSoon } from "@/components/layout/ComingSoon";

export const Route = createFileRoute("/_app/orders/new")({
  component: NewOrder,
});

/** Quick order, the main flow (F6). */
function NewOrder() {
  const { t } = useTranslation();
  return <ComingSoon title={t("orders.new")} />;
}
