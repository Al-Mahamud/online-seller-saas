import { Link } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";
import { useTranslation } from "react-i18next";

export function BackToOrders() {
  const { t } = useTranslation();
  return (
    <Link to="/orders" className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline">
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("nav.orders")}
    </Link>
  );
}
