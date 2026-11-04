import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Phone, Printer } from "lucide-react";
import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { Button } from "@/components/ui/button";
import { usePlaceName } from "@/features/delivery/geo";
import { ON_DARK_FOCUS } from "@/lib/focus";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type Order, orderChanged, orderErrorText, orderQuery, slipsUrl, stillOut } from "./api";
import { BackToOrders } from "./BackToOrders";
import { CancelDialog, PaymentDialog, ShipDialog } from "./OrderDialogs";
import { DeliverDialog, ReturnDialog } from "./ReturnDialogs";
import { StatusBadge } from "./StatusBadge";
import type { OrderStatus } from "./search";

const route = getRouteApi("/_app/orders/$orderId/");

type DialogName = "ship" | "deliver" | "failed" | "return" | "payment" | "cancel";

const CARD = "flex flex-col gap-2.5 rounded-[14px] border bg-card p-3.5 md:px-5 md:py-4";
const MAIN_ACTION = cn(
  "h-13 cursor-pointer rounded-[10px] bg-marigold-text px-4.5 text-lg font-bold text-ledger hover:bg-marigold disabled:opacity-60 md:h-11.5 md:text-base",
  ON_DARK_FOCUS,
);
const SECOND_ACTION = cn(
  "h-11.5 cursor-pointer rounded-[10px] border-[1.5px] border-ledger-text px-3.5 text-base font-semibold text-white hover:bg-white/10 disabled:opacity-60 md:text-[15px]",
  ON_DARK_FOCUS,
);
const FLOW: OrderStatus[] = ["DRAFT", "CONFIRMED", "PACKED", "SHIPPED", "DELIVERED"];

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

  // The one next step stands out; only what applies to this status is offered.
  const actions = (
    <div className="flex flex-col gap-2 md:flex-[1_1_240px] md:flex-row-reverse md:flex-wrap">
      {order.status === "DRAFT" && (
        <button
          type="button"
          className={MAIN_ACTION}
          disabled={step.isPending}
          onClick={() => step.mutate({ path: "confirm", current: order })}
        >
          {t("orders.confirm")}
        </button>
      )}
      {order.status === "CONFIRMED" && (
        <button
          type="button"
          className={MAIN_ACTION}
          disabled={step.isPending}
          onClick={() => step.mutate({ path: "pack", current: order })}
        >
          {t("orders.pack")}
        </button>
      )}
      {canShip && (
        <button
          type="button"
          className={order.status === "CONFIRMED" ? SECOND_ACTION : MAIN_ACTION}
          onClick={() => setDialog("ship")}
        >
          {t(order.status === "RETURNED" ? "orders.shipAgain" : "orders.ship")}
        </button>
      )}
      {order.status === "SHIPPED" && (
        <button type="button" className={MAIN_ACTION} onClick={() => setDialog("deliver")}>
          {t("orders.deliver")}
        </button>
      )}
      {order.status === "SHIPPED" && (
        <button type="button" className={SECOND_ACTION} onClick={() => setDialog("failed")}>
          {t("orders.failedDelivery")}
        </button>
      )}
      {canReturn && (
        <button type="button" className={SECOND_ACTION} onClick={() => setDialog("return")}>
          {t("orders.customerReturn")}
        </button>
      )}
    </div>
  );
  const smallButton = "h-11 rounded-[10px] border-[1.5px] text-[15px] font-semibold max-md:h-12 max-md:text-base";
  const tools = (
    <>
      {payable && (
        <Button variant="outline" className={smallButton} onClick={() => setDialog("payment")}>
          {t("orders.addPayment")}
        </Button>
      )}
      {editable && (
        <Button asChild variant="outline" className={smallButton}>
          <Link
            to="/orders/$orderId/edit"
            params={{ orderId: order.id }}
            className="text-foreground no-underline hover:text-foreground"
          >
            {t("orders.edit")}
          </Link>
        </Button>
      )}
      <Button asChild variant="outline" className={smallButton}>
        <a
          href={slipsUrl([order.id])}
          target="_blank"
          rel="noopener"
          className="text-foreground no-underline hover:text-foreground"
        >
          <Printer aria-hidden="true" />
          {t("orders.printSlip")}
        </a>
      </Button>
    </>
  );
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title, the progress and the next step share one dark header. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-3 max-md:bg-ledger max-md:pt-1 max-md:pr-4.5 max-md:pb-8.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="flex flex-wrap items-end justify-between gap-x-5 gap-y-2.5">
          <div>
            <div className="max-md:[&_a]:text-marigold-text">
              <BackToOrders />
            </div>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">
                {format.orderNumber(order.numberLabel)}
              </h1>
              <StatusBadge status={order.status} />
            </div>
            <p className="text-[15px] max-md:text-ledger-text md:text-muted-foreground">
              {format.dateTime(order.createdAt)}
            </p>
          </div>
          <div className="flex flex-wrap gap-2.5 max-md:hidden">{tools}</div>
        </div>
        <section
          aria-label={t("orders.detail.status")}
          className="flex flex-col gap-3 md:relative md:flex-row md:flex-wrap md:items-center md:justify-between md:gap-x-8 md:gap-y-4 md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-5 md:pr-6 md:pl-14 md:text-white"
        >
          <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
          <Progress order={order} />
          {actions}
        </section>
      </div>

      <div className="flex flex-col gap-3.5 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-4 md:gap-4">
        {order.cancelReason && (
          <p className="rounded-[10px] bg-muted px-3.5 py-3">
            <span className="font-semibold">{t("orders.reason")}:</span> {order.cancelReason}
          </p>
        )}
        {/* Two columns on a laptop. On phones the customer comes first, for calling and packing. */}
        <div className="flex flex-col gap-3.5 lg:flex-row lg:items-start lg:gap-4">
          <div className="contents lg:flex lg:min-w-0 lg:flex-[7_1_0%] lg:flex-col lg:gap-4">
            <Items order={order} />
            <Timeline order={order} />
          </div>
          <div className="contents lg:flex lg:min-w-0 lg:flex-[5_1_0%] lg:flex-col lg:gap-4">
            <Parties order={order} />
            <Shipments order={order} />
            <Payments order={order} />
          </div>
        </div>
        <div className="flex flex-col gap-2 md:hidden">{tools}</div>
        {(editable || order.status === "PACKED") && (
          <button
            type="button"
            className="min-h-12 w-full cursor-pointer rounded-[10px] bg-destructive px-5 text-base font-bold text-white hover:bg-danger-ink focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-destructive md:min-h-11.5 md:w-fit md:self-end"
            onClick={() => setDialog("cancel")}
          >
            {t("orders.cancel")}
          </button>
        )}
      </div>

      {dialog === "ship" && <ShipDialog order={order} onClose={close} />}
      {dialog === "deliver" && <DeliverDialog order={order} onClose={close} />}
      {dialog === "failed" && <ReturnDialog order={order} kind="FAILED_DELIVERY" onClose={close} />}
      {dialog === "return" && <ReturnDialog order={order} kind="CUSTOMER_RETURN" onClose={close} />}
      {dialog === "payment" && <PaymentDialog order={order} onClose={close} />}
      {dialog === "cancel" && <CancelDialog order={order} onClose={close} />}
    </div>
  );
}

type Stage = { status: OrderStatus; at: string | null; state: "done" | "now" | "later" };

/** The steps this order took or has ahead. A step may have been skipped: confirmed goes straight to the courier. */
function stagesOf(order: Order): Stage[] {
  const times: Partial<Record<OrderStatus, string | null | undefined>> = {
    DRAFT: order.createdAt,
    CONFIRMED: order.confirmedAt,
    PACKED: order.packedAt,
    SHIPPED: order.shippedAt,
    DELIVERED: order.deliveredAt,
    RETURNED: order.returnedAt,
    CANCELLED: order.cancelledAt,
  };
  const at = (status: OrderStatus) => times[status] ?? null;
  if (order.status === "CANCELLED") {
    return [
      ...FLOW.filter((status) => at(status)).map((status): Stage => ({ status, at: at(status), state: "done" })),
      { status: "CANCELLED", at: at("CANCELLED"), state: "now" },
    ];
  }
  if (order.status === "RETURNED") {
    return [
      ...FLOW.filter((status) => status !== "DELIVERED" || at(status)).map(
        (status): Stage => ({ status, at: at(status), state: "done" }),
      ),
      { status: "RETURNED", at: at("RETURNED"), state: "now" },
    ];
  }
  const current = FLOW.indexOf(order.status);
  return FLOW.map((status, index) => ({
    status,
    at: index <= current ? at(status) : null,
    state: index < current ? "done" : index === current ? "now" : "later",
  }));
}

function Progress({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  const stages = stagesOf(order);
  const next = stages.find((stage) => stage.state === "later");
  return (
    <>
      {/* A short bar on phones, where the five names wouldn't fit. */}
      <div className="flex flex-col gap-1.5 md:hidden">
        <div aria-hidden="true" className="grid auto-cols-fr grid-flow-col gap-1">
          {stages.map((stage) => (
            <div
              key={stage.status}
              className={cn("h-1.25 rounded-full", stage.state === "later" ? "bg-ledger-outline" : "bg-marigold")}
            />
          ))}
        </div>
        <p className="flex flex-wrap items-baseline justify-between gap-x-2.5">
          <span className="text-[15px] font-bold text-marigold-text">
            {t("orders.detail.now", { status: t(`orderStatus.${order.status}`) })}
          </span>
          {next && (
            <span className="text-sm text-ledger-text">
              {t("orders.detail.next", { status: t(`orderStatus.${next.status}`) })}
            </span>
          )}
        </p>
      </div>
      <ol
        className="hidden min-w-0 flex-[3_1_480px] grid-flow-col pt-1.75 md:grid"
        style={{ gridAutoColumns: "minmax(0, 1fr)" }}
      >
        {stages.map((stage, index) => (
          <li
            key={stage.status}
            aria-current={stage.state === "now" ? "step" : undefined}
            className={cn(
              "relative border-t-2 pt-3.5 pr-2",
              index === stages.length - 1
                ? "border-transparent"
                : stage.state === "done"
                  ? "border-marigold"
                  : "border-ledger-outline",
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                "absolute rounded-full",
                stage.state === "now"
                  ? "-top-2.75 -left-0.75 size-5 border-[5px] border-marigold bg-white"
                  : "-top-2 left-0 size-3.5",
                stage.state === "done" && "bg-marigold",
                stage.state === "later" && "border-2 border-ledger-outline bg-ledger",
              )}
            />
            <p
              className={cn(
                "text-[15px]",
                stage.state === "now" && "font-bold text-marigold-text",
                stage.state === "done" && "font-semibold",
                stage.state === "later" && "text-ledger-text",
              )}
            >
              {t(`orderStatus.${stage.status}`)}
            </p>
            {stage.at && <p className="text-[13px] text-ledger-text">{format.dateTime(stage.at)}</p>}
          </li>
        ))}
      </ol>
    </>
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
    <section aria-labelledby="order-customer" className={cn(CARD, "max-lg:order-first")}>
      <h2 id="order-customer" className="text-[19px] font-bold">
        {t("orders.customer")}
      </h2>
      <div className="flex items-center justify-between gap-2.5">
        <div className="min-w-0">
          <Link
            to="/customers/$customerId"
            params={{ customerId: customer.id }}
            className="block w-fit font-bold text-primary"
          >
            {customer.name}
          </Link>
          <p className="text-[15px] text-[#2B342F]">{format.phone(customer.phone)}</p>
        </div>
        <a
          href={`tel:${customer.phone}`}
          aria-label={t("orders.detail.callLabel", { name: customer.name })}
          className="flex h-11 flex-none items-center gap-1.5 rounded-[10px] border-[1.5px] border-primary px-3 text-[15px] font-semibold text-primary no-underline hover:bg-accent"
        >
          <Phone aria-hidden="true" className="size-4.5" />
          {t("orders.detail.call")}
        </a>
      </div>
      {(otherRecipient || delivery.address || place) && (
        <div className="border-t pt-2.5">
          <p className="text-sm text-muted-foreground">{t("orders.deliverTo")}</p>
          {otherRecipient && (
            <p className="mt-0.5">
              <span className="font-semibold">{delivery.name}</span>{" "}
              <a href={`tel:${delivery.phone}`} className="text-primary">
                {format.phone(delivery.phone)}
              </a>
            </p>
          )}
          {delivery.address && <p className="mt-0.5 text-[15px] whitespace-pre-wrap">{delivery.address}</p>}
          {place && <p className="text-[15px]">{place}</p>}
        </div>
      )}
      {order.note && <p className="rounded-lg bg-muted px-2.5 py-2 text-sm whitespace-pre-wrap">{order.note}</p>}
    </section>
  );
}

function Line({ label, children, strong }: { label: string; children: ReactNode; strong?: boolean }) {
  return (
    <div className={cn("flex justify-between gap-3", strong && "border-t pt-1.5 font-bold")}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

/** A big line for what's still to happen with the money. */
function Due({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mt-1.5 flex items-center justify-between gap-2.5 rounded-[10px] bg-accent px-3 py-2.5 text-base font-bold text-accent-foreground md:text-[17px]">
      <dt>{label}</dt>
      <dd className="text-[19px] md:text-xl">{children}</dd>
    </div>
  );
}

function Items({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  const settled = order.status === "DELIVERED" || order.status === "RETURNED";
  return (
    <section aria-labelledby="order-items" className={cn(CARD, "gap-0")}>
      <h2 id="order-items" className="mb-2 text-[19px] font-bold">
        {t("orders.items")}
      </h2>
      <ul className="flex flex-col">
        {order.items.map((item) => (
          <li key={item.id} className="flex items-center justify-between gap-3 border-t py-2.25">
            <span className="min-w-0">
              <span className="block font-semibold break-words">{item.productName}</span>
              <span className="block text-sm text-muted-foreground">
                {[item.variantLabel, `${format.number(item.quantity)} × ${format.taka(item.unitPrice)}`]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
            </span>
            <span className="flex-none font-bold">{format.taka(item.amount)}</span>
          </li>
        ))}
      </ul>
      <dl className="flex flex-col gap-1 border-t pt-2.5 text-[15px]">
        <Line label={t("orders.subtotal")}>{format.taka(order.subtotal)}</Line>
        {order.discount > 0 && <Line label={t("orders.discountShort")}>−{format.taka(order.discount)}</Line>}
        <Line label={t("orders.deliveryChargeShort")}>{format.taka(order.deliveryCharge)}</Line>
        <Line label={t("orders.total")} strong>
          {format.taka(order.total)}
        </Line>
        {order.returnCredit > 0 && <Line label={t("orders.returnCredit")}>−{format.taka(order.returnCredit)}</Line>}
        {order.amountPaid !== 0 && <Line label={t("orders.amountPaid")}>{format.taka(order.amountPaid)}</Line>}
        {!settled && order.status !== "CANCELLED" && <Due label={t("orders.cod")}>{format.taka(order.codAmount)}</Due>}
        {settled && order.balanceDue > 0 && <Due label={t("orders.balanceDue")}>{format.taka(order.balanceDue)}</Due>}
        {order.balanceDue < 0 && <Due label={t("orders.refundDue")}>{format.taka(-order.balanceDue)}</Due>}
      </dl>
    </section>
  );
}

function Payments({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <section aria-labelledby="order-payments" className={CARD}>
      <h2 id="order-payments" className="text-[19px] font-bold">
        {t("orders.detail.payments")}
      </h2>
      {order.payments.length === 0 && (
        <p className="text-[15px] text-muted-foreground">{t("orders.detail.noPayments")}</p>
      )}
      {order.payments.length > 0 && (
        <ul className="flex flex-col">
          {order.payments.map((payment) => (
            <li key={payment.id} className="flex justify-between gap-3 border-t py-2 text-[15px]">
              <span className="min-w-0">
                <span className="block font-semibold">{t(`paymentMethod.${payment.method}`)}</span>
                <span className="block text-sm text-muted-foreground">
                  {[payment.reference, format.date(payment.receivedAt)].filter(Boolean).join(" · ")}
                </span>
              </span>
              <span className="flex-none font-bold">{format.taka(payment.amount)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Shipments({ order }: { order: Order }) {
  const { t } = useTranslation();
  const format = useFormat();
  if (order.shipments.length === 0) {
    return null;
  }
  return (
    <section aria-labelledby="order-courier" className={CARD}>
      <h2 id="order-courier" className="text-[19px] font-bold">
        {t("orders.shipments")}
      </h2>
      <ul className="flex flex-col gap-2.5">
        {order.shipments.map((shipment) => (
          <li key={shipment.id} className="border-t pt-2.5 first:border-t-0 first:pt-0">
            <p className="flex flex-wrap items-baseline justify-between gap-x-2.5">
              <span className="font-bold">{shipment.courier}</span>
              {shipment.trackingNo && <span className="text-[15px] text-[#2B342F]">{shipment.trackingNo}</span>}
            </p>
            <p className="text-sm text-muted-foreground">
              {t("orders.detail.sent", { time: format.dateTime(shipment.shippedAt) })}
            </p>
            {shipment.codExpected > 0 && (
              <p className="text-[15px] font-semibold">
                {shipment.codCollected === null
                  ? t("orders.codToCollect", { amount: format.taka(shipment.codExpected) })
                  : t("orders.codCollected", { amount: format.taka(shipment.codCollected) })}
                {shipment.settledAt && ` · ${t("orders.codSettled")}`}
              </p>
            )}
          </li>
        ))}
      </ul>
    </section>
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
    // The latest first.
  ].sort((a, b) => b.at.localeCompare(a.at));
  return (
    <section aria-labelledby="order-history" className={cn(CARD, "gap-0 max-lg:order-last")}>
      <h2 id="order-history" className="mb-2 text-[19px] font-bold">
        {t("orders.timeline")}
      </h2>
      <ol className="flex flex-col">
        {steps.map((step) => (
          <li key={step.key} className="border-t py-2 last:pb-0">
            <p className="text-[15px] font-semibold">{step.title}</p>
            {step.detail && <p className="text-[15px] whitespace-pre-wrap">{step.detail}</p>}
            <p className="text-sm text-muted-foreground">
              {format.dateTime(step.at)}
              {step.by && ` · ${step.by}`}
            </p>
          </li>
        ))}
      </ol>
    </section>
  );
}
