import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { ComingSoon } from "@/components/layout/ComingSoon";

export const Route = createFileRoute("/orders/")({
  component: Orders,
});

/** Orders by status (F6). */
function Orders() {
  const { t } = useTranslation();
  return <ComingSoon title={t("nav.orders")} />;
}
