import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Printer } from "lucide-react";
import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { usePlaceName } from "@/features/delivery/geo";
import { useFormat } from "@/lib/format";
import { type Order, orderChanged, orderErrorText, orderQuery, slipsUrl, stillOut } from "./api";
import { BackToOrders } from "./BackToOrders";
import { CancelDialog, DeliverDialog, PaymentDialog, ReturnDialog, ShipDialog } from "./OrderDialogs";
import { StatusBadge } from "./StatusBadge";

const route = getRouteApi("/_app/orders/$orderId/");

type DialogName = "ship" | "deliver" | "failed" | "return" | "payment" | "cancel";

export function OrderPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const { orderId } = route.useParams();
  const queryClient = useQueryClient();
  const { data: order } = useQuery(orderQuery(orderId));
  const [dialog, setDialog] = useState<DialogName>();
  const step = useMutation({
    mutationFn: ({ path, current }: { path: "confirm" | "pack"; current: Order }) =>
      call(
        path === "confirm"
          ? api.POST("/api/v1/orders/{id}/confirm", {
              params: { path: { id: current.id } },
              body: { version: current.version },
            })
          : api.POST("/api/v1/orders/{id}/pack", {
              params: { path: { id: current.id } },
              body: { version: current.version },
            }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: (saved) => {
      orderChanged(queryClient, saved);
      toast.success(t(`orders.moved.${saved.status}`));
    },
    onError: (error) => {
      toast.error(orderErrorText(error), { duration: 10_000 });
      if (error instanceof ApiError) {
        void queryClient.invalidateQueries({ queryKey: ["order", orderId] });
      }
    },
  });
  if (!order) {
    return <BackToOrders />;
  }
  const close = () => setDialog(undefined);
  const editable = order.status === "DRAFT" || order.status === "CONFIRMED";
  const payable = ["DRAFT", "CONFIRMED", "PACKED", "DELIVERED"].includes(order.status) && order.balanceDue > 0;
  const canReturn = order.status === "DELIVERED" && [...stillOut(order).values()].some((count) => count > 0);
  const canShip =
    order.status === "CONFIRMED" || order.status === "PACKED" || (order.status === "RETURNED" && !order.deliveredAt);
  return (
    <div className="flex max-w-2xl flex-col gap-4">
      <div>
        <BackToOrders />
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <h1 className="text-2xl font-bold tracking-tight md:text-3xl">{format.orderNumber(order.numberLabel)}</h1>
          <StatusBadge status={order.status} />
        </div>
        <p className="text-muted-foreground">{format.dateTime(order.createdAt)}</p>
      </div>

      <div className="flex flex-wrap gap-2">
        {order.status === "DRAFT" && (
          <Button disabled={step.isPending} onClick={() => step.mutate({ path: "confirm", current: order })}>
            {t("orders.confirm")}
          </Button>
        )}
        {order.status === "CONFIRMED" && (
          <Button disabled={step.isPending} onClick={() => step.mutate({ path: "pack", current: order })}>
            {t("orders.pack")}
          </Button>
        )}
        {canShip && (
          <Button variant={order.status === "CONFIRMED" ? "outline" : "default"} onClick={() => setDialog("ship")}>
            {t(order.status === "RETURNED" ? "orders.shipAgain" : "orders.ship")}
          </Button>
        )}
        {order.status === "SHIPPED" && <Button onClick={() => setDialog("deliver")}>{t("orders.deliver")}</Button>}
        {order.status === "SHIPPED" && (
          <Button variant="outline" onClick={() => setDialog("failed")}>
            {t("orders.failedDelivery")}
          </Button>
        )}
        {canReturn && (
          <Button variant="outline" onClick={() => setDialog("return")}>
            {t("orders.customerReturn")}
          </Button>
        )}
        {payable && (
          <Button variant="outline" onClick={() => setDialog("payment")}>
            {t("orders.addPayment")}
          </Button>
        )}
        {editable && (
          <Button asChild variant="outline">
            <Link to="/orders/$orderId/edit" params={{ orderId: order.id }}>
              {t("orders.edit")}
            </Link>
          </Button>
        )}
        <Button asChild variant="outline">
          <a href={slipsUrl([order.id])} target="_blank" rel="noopener">
            <Printer aria-hidden="true" />
            {t("orders.printSlip")}
          </a>
        </Button>
        {(editable || order.status === "PACKED") && (
          <Button variant="outline" onClick={() => setDialog("cancel")}>
            {t("orders.cancel")}
          </Button>
        )}
      </div>

      {order.cancelReason && <p className="rounded-lg bg-muted p-3">{order.cancelReason}</p>}

      <Parties order={order} />
      <Items order={order} />
      <Money order={order} />
      <Shipments order={order} />
      <Timeline order={order} />

      {dialog === "ship" && <ShipDialog order={order} onClose={close} />}
      {dialog === "deliver" && <DeliverDialog order={order} onClose={close} />}
      {dialog === "failed" && <ReturnDialog order={order} kind="FAILED_DELIVERY" onClose={close} />}
      {dialog === "return" && <ReturnDialog order={order} kind="CUSTOMER_RETURN" onClose={close} />}
      {dialog === "payment" && <PaymentDialog order={order} onClose={close} />}
      {dialog === "cancel" && <CancelDialog order={order} onClose={close} />}
    </div>
  );
}

function Parties({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  const placeName = usePlaceName();
  const { customer, delivery } = order;
  const place = [delivery.area, delivery.district].flatMap((part) => (part ? [placeName(part)] : [])).join(", ");
  const otherRecipient = delivery.name !== customer.name || delivery.phone !== customer.phone;
  return (
    <Section title={t("orders.customer")}>
      <div className="flex flex-col gap-1">
        <Link
          to="/customers/$customerId"
          params={{ customerId: customer.id }}
          className="w-fit font-semibold text-primary"
        >
          {customer.name}
        </Link>
        <a href={`tel:${customer.phone}`} className="w-fit text-primary">
          {format.phone(customer.phone)}
        </a>
        {otherRecipient && (
          <p className="mt-2">
            {t("orders.goesTo")}: <span className="font-semibold">{delivery.name}</span>{" "}
            <a href={`tel:${delivery.phone}`} className="text-primary">
              {format.phone(delivery.phone)}
            </a>
          </p>
        )}
        {delivery.address && <p className="mt-2 whitespace-pre-wrap">{delivery.address}</p>}
        {place && <p className="text-muted-foreground">{place}</p>}
        {order.note && <p className="mt-2 rounded-lg bg-muted p-2 text-sm whitespace-pre-wrap">{order.note}</p>}
      </div>
    </Section>
  );
}

function Items({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <Section title={t("orders.items")}>
      <ul className="flex flex-col">
        {order.items.map((item) => (
          <li key={item.id} className="flex items-start justify-between gap-3 border-t py-2 first:border-t-0">
            <span className="min-w-0">
              <span className="block font-medium break-words">
                {[item.productName, item.variantLabel].filter(Boolean).join(" · ")}
              </span>
              <span className="block text-sm text-muted-foreground">
                {format.number(item.quantity)} × {format.taka(item.unitPrice)}
              </span>
            </span>
            <span className="flex-none font-semibold">{format.taka(item.amount)}</span>
          </li>
        ))}
      </ul>
    </Section>
  );
}

function Line({ label, children, strong }: { label: string; children: ReactNode; strong?: boolean }) {
  return (
    <div className={`flex justify-between gap-3 ${strong ? "border-t pt-2 font-bold" : ""}`}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function Money({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  const settled = order.status === "DELIVERED" || order.status === "RETURNED";
  return (
    <Section title={t("orders.money")}>
      <dl className="flex flex-col gap-1">
        <Line label={t("orders.subtotal")}>{format.taka(order.subtotal)}</Line>
        {order.discount > 0 && <Line label={t("orders.discountShort")}>−{format.taka(order.discount)}</Line>}
        <Line label={t("orders.deliveryChargeShort")}>{format.taka(order.deliveryCharge)}</Line>
        <Line label={t("orders.total")} strong>
          {format.taka(order.total)}
        </Line>
        {order.returnCredit > 0 && <Line label={t("orders.returnCredit")}>−{format.taka(order.returnCredit)}</Line>}
        {order.amountPaid !== 0 && <Line label={t("orders.amountPaid")}>{format.taka(order.amountPaid)}</Line>}
        {!settled && order.status !== "CANCELLED" && (
          <Line label={t("orders.cod")} strong>
            {format.taka(order.codAmount)}
          </Line>
        )}
        {settled && order.balanceDue > 0 && (
          <Line label={t("orders.balanceDue")} strong>
            {format.taka(order.balanceDue)}
          </Line>
        )}
        {order.balanceDue < 0 && (
          <Line label={t("orders.refundDue")} strong>
            {format.taka(-order.balanceDue)}
          </Line>
        )}
      </dl>
      {order.payments.length > 0 && (
        <ul className="flex flex-col gap-1 border-t pt-2 text-sm text-muted-foreground">
          {order.payments.map((payment) => (
            <li key={payment.id} className="flex justify-between gap-3">
              <span>
                {t(`paymentMethod.${payment.method}`)}
                {payment.reference && ` · ${payment.reference}`} · {format.date(payment.receivedAt)}
              </span>
              <span>{format.taka(payment.amount)}</span>
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}

function Shipments({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  if (order.shipments.length === 0) {
    return null;
  }
  return (
    <Section title={t("orders.shipments")}>
      <ul className="flex flex-col gap-3">
        {order.shipments.map((shipment) => (
          <li key={shipment.id} className="border-t pt-3 first:border-t-0 first:pt-0">
            <p className="font-semibold">
              {shipment.courier}
              {shipment.trackingNo && <span className="font-normal"> · {shipment.trackingNo}</span>}
            </p>
            <p className="text-sm text-muted-foreground">{format.dateTime(shipment.shippedAt)}</p>
            {shipment.codExpected > 0 && (
              <p>
                {shipment.codCollected === null
                  ? t("orders.codToCollect", { amount: format.taka(shipment.codExpected) })
                  : t("orders.codCollected", { amount: format.taka(shipment.codCollected) })}
                {shipment.settledAt && ` · ${t("orders.codSettled")}`}
              </p>
            )}
          </li>
        ))}
      </ul>
    </Section>
  );
}

function Timeline({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  const steps = [
    ...order.events.map((event) => ({
      key: `${event.to}-${event.at}`,
      at: event.at,
      title: t(`orderStatus.${event.to}`),
      // A partial delivery's details are in its return, in the seller's language.
      detail: event.to === "DELIVERED" ? "" : event.note,
      by: event.by,
    })),
    ...order.returns.map((back) => ({
      key: back.id,
      at: back.createdAt,
      title: t(`orders.returnKind.${back.kind}`),
      detail: [
        back.reason,
        ...back.items.map(
          (item) =>
            `${[item.productName, item.variantLabel].filter(Boolean).join(" · ")} × ${format.number(item.quantity)} (${t(`orders.disposition.${item.disposition}`)})`,
        ),
        back.refundAmount > 0 ? t("orders.refunded", { amount: format.taka(back.refundAmount) }) : "",
      ]
        .filter(Boolean)
        .join("\n"),
      by: null,
    })),
  ].sort((a, b) => a.at.localeCompare(b.at));
  return (
    <Section title={t("orders.timeline")}>
      <ol className="flex flex-col gap-3">
        {steps.map((step) => (
          <li key={step.key} className="border-l-2 border-primary pl-3">
            <p className="font-semibold">{step.title}</p>
            {step.detail && <p className="whitespace-pre-wrap">{step.detail}</p>}
            <p className="text-sm text-muted-foreground">
              {format.dateTime(step.at)}
              {step.by && ` · ${step.by}`}
            </p>
          </li>
        ))}
      </ol>
    </Section>
  );
}
