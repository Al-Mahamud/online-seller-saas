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
  return (
    <div className="flex max-w-2xl flex-col gap-4">
      <div>
        <Link
          to="/orders/$orderId"
          params={{ orderId }}
          className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline"
        >
          <ChevronLeft aria-hidden="true" className="size-5" />
          {order ? format.orderNumber(order.numberLabel) : t("nav.orders")}
        </Link>
        <h1 className="text-2xl font-bold tracking-tight md:text-3xl">{t("orders.edit")}</h1>
      </div>
      {order && <OrderForm key={order.id} order={order} />}
    </div>
  );
}
