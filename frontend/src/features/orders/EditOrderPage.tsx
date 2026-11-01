import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useFormat } from "@/lib/format";
import { orderQuery } from "./api";
import { OrderForm } from "./OrderForm";

const route = getRouteApi("/_app/orders/$orderId/edit");

/** A draft or a confirmed order; once packed it no longer changes. */
export function EditOrderPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const { orderId } = route.useParams();
  const { data: order } = useQuery({ ...orderQuery(orderId), refetchOnWindowFocus: false });
  const heading = (
    <>
      <Link
        to="/orders/$orderId"
        params={{ orderId }}
        className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline"
      >
        <ChevronLeft aria-hidden="true" className="size-5" />
        {order ? format.orderNumber(order.numberLabel) : t("nav.orders")}
      </Link>
      <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("orders.edit")}</h1>
    </>
  );
  return order ? <OrderForm key={order.id} order={order} heading={heading} /> : heading;
}
