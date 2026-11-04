import { Link } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";
import { useTranslation } from "react-i18next";

/** The way back to the product list, above a product's own pages. */
export function BackToProducts() {
  const { t } = useTranslation();
  return (
    <Link to="/products" className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline">
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("nav.products")}
    </Link>
  );
}
