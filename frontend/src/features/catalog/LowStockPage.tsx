import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";
import { useTranslation } from "react-i18next";
import { PageHeader } from "@/components/layout/PageHeader";
import { AddressPagination } from "@/components/layout/Pagination";
import { useFormat } from "@/lib/format";
import { lowStockQuery } from "./api";

const route = getRouteApi("/_app/products/low-stock");

/** What's running low, emptiest first. The low-stock email links here. */
export function LowStockPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const paging = route.useSearch();
  const lowStock = useQuery(lowStockQuery(paging));
  const items = lowStock.data?.items;
  return (
    <div className="flex max-w-2xl flex-col gap-3">
      <div>
        <BackToProducts />
        <PageHeader title={t("products.lowStockTitle")} />
      </div>
      {items?.length === 0 && <p className="text-muted-foreground">{t("products.lowStockNone")}</p>}
      {!!items?.length && (
        <ul className="flex flex-col rounded-xl border bg-card px-4">
          {items.map((item) => (
            <li key={item.variantId} className="border-t first:border-t-0">
              <Link
                to="/products/$productId"
                params={{ productId: item.productId }}
                className="flex items-center justify-between gap-3 py-3 text-foreground no-underline"
              >
                <span className="min-w-0">
                  <span className="block truncate font-semibold">{item.productName}</span>
                  <span className="block text-sm text-muted-foreground">{item.label || item.sku}</span>
                </span>
                <span className="flex-none text-right">
                  <span className="block font-bold text-warning-foreground">
                    {t("products.available", { count: format.number(item.available) })}
                  </span>
                  <span className="block text-xs text-muted-foreground">
                    {t("products.alertAt", { count: format.number(item.threshold) })}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <AddressPagination result={lowStock.data} />
    </div>
  );
}

export function BackToProducts() {
  const { t } = useTranslation();
  return (
    <Link to="/products" className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline">
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("nav.products")}
    </Link>
  );
}
