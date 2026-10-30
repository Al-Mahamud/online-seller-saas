import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { ComingSoon } from "@/components/layout/ComingSoon";

export const Route = createFileRoute("/_app/products")({
  component: Products,
});

/** Products and stock (F3). */
function Products() {
  const { t } = useTranslation();
  return <ComingSoon title={t("nav.products")} />;
}
