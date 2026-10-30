import { getRouteApi } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { BackToOrders } from "./BackToOrders";
import { OrderForm } from "./OrderForm";

const route = getRouteApi("/_app/orders/new");

/** Quick order, the main flow: from a chat, on a phone, in under a minute. */
export function NewOrderPage() {
  const { t } = useTranslation();
  const { phone } = route.useSearch();
  return (
    <div className="flex max-w-2xl flex-col gap-4">
      <div>
        <BackToOrders />
        <h1 className="text-2xl font-bold tracking-tight md:text-3xl">{t("orders.new")}</h1>
      </div>
      <OrderForm initialPhone={phone} />
    </div>
  );
}
