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
    <OrderForm
      initialPhone={phone}
      heading={
        <>
          <BackToOrders />
          <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("orders.new")}</h1>
        </>
      }
    />
  );
}
