import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call, newIdempotencyKey } from "@/api/client";
import { ApiError } from "@/api/errors";
import { AreaField, Field, SelectBox } from "@/components/form/Field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useFormat } from "@/lib/format";
import { parseCount, parseTaka, takaInput } from "@/lib/money";
import {
  type Disposition,
  type Order,
  type OrderItem,
  orderChanged,
  orderErrorText,
  PAYMENT_METHODS,
  type PaymentMethod,
  stillOut,
} from "./api";
import { CourierField } from "./CourierField";

const MAX_REASON = 500;

/** A change made in a dialog: the answer updates the page; a refusal stays in the dialog, with the order read again. */
function useOrderChange<V>(order: Order, send: (variables: V) => Promise<Order>, doneText: string, onDone: () => void) {
  const queryClient = useQueryClient();
  const [problem, setProblem] = useState<string>();
  const change = useMutation({
    mutationFn: send,
    meta: { showsOwnErrors: true },
    onMutate: () => setProblem(undefined),
    onSuccess: (saved) => {
      orderChanged(queryClient, saved);
      toast.success(doneText);
      onDone();
    },
    onError: (error) => {
      setProblem(orderErrorText(error));
      if (error instanceof ApiError) {
        void queryClient.invalidateQueries({ queryKey: ["order", order.id] });
      }
    },
  });
  return { change, problem, setProblem };
}

function OrderDialog({
  title,
  action,
  problem,
  pending,
  onSubmit,
  onClose,
  children,
}: {
  title: string;
  action: string;
  problem?: string;
  pending: boolean;
  onSubmit: () => void;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          {problem && (
            <Alert variant="destructive">
              <AlertDescription>{problem}</AlertDescription>
            </Alert>
          )}
          {children}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("page.cancel")}
          </Button>
          <Button disabled={pending} onClick={onSubmit}>
            {action}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const lineName = (item: { productName: string; variantLabel: string }) =>
  [item.productName, item.variantLabel].filter(Boolean).join(" · ");

export function ShipDialog({ order, onClose }: { order: Order; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [courier, setCourier] = useState("");
  const [tracking, setTracking] = useState("");
  const [charge, setCharge] = useState("");
  const [errors, setErrors] = useState<{ courier?: string; charge?: string }>({});
  const { change, problem } = useOrderChange(
    order,
    (courierCharge: number | undefined) =>
      call(
        api.POST("/api/v1/orders/{id}/ship", {
          params: { path: { id: order.id } },
          body: {
            courier: courier.trim(),
            trackingNo: tracking.trim() || undefined,
            courierCharge,
            version: order.version,
          },
        }),
      ),
    t("orders.moved.SHIPPED"),
    onClose,
  );
  function submit() {
    const courierCharge = charge.trim() ? parseTaka(charge) : undefined;
    const wrong = {
      courier: courier.trim() ? undefined : t("field.required"),
      charge: courierCharge === null ? t("field.amountInvalid") : undefined,
    };
    setErrors(wrong);
    if (!wrong.courier && !wrong.charge && courierCharge !== null) {
      change.mutate(courierCharge);
    }
  }
  return (
    <OrderDialog
      title={t("orders.shipTitle", { number: format.orderNumber(order.numberLabel) })}
      action={t("orders.ship")}
      problem={problem}
      pending={change.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <CourierField value={courier} onChange={setCourier} error={errors.courier} />
      <Field
        label={t("orders.tracking")}
        autoComplete="off"
        maxLength={100}
        value={tracking}
        onChange={(event) => setTracking(event.target.value)}
      />
      <Field
        label={t("orders.courierCharge")}
        hint={t("orders.courierChargeHint")}
        inputMode="decimal"
        value={charge}
        error={errors.charge}
        onChange={(event) => setCharge(event.target.value)}
      />
    </OrderDialog>
  );
}

interface Coming {
  quantity: string;
  disposition: Disposition;
}

/** How many of each item, and what becomes of them: back on the shelf, or written off. */
function ItemsBack({
  items,
  most,
  lines,
  wholeOnly,
  onChange,
}: {
  items: OrderItem[];
  most: (item: OrderItem) => number;
  lines: Record<string, Coming>;
  wholeOnly?: boolean;
  onChange: (id: string, line: Coming) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <>
      {items
        .filter((item) => most(item) > 0)
        .map((item) => {
          const line = lines[item.id] ?? { quantity: "", disposition: "RESTOCK" as Disposition };
          return (
            <fieldset key={item.id} aria-label={lineName(item)} className="flex flex-col gap-2 rounded-lg border p-3">
              <p className="font-semibold">{lineName(item)}</p>
              {!wholeOnly && (
                <Field
                  label={t("orders.howMany")}
                  hint={t("orders.outOf", { count: format.number(most(item)) })}
                  inputMode="numeric"
                  value={line.quantity}
                  onChange={(event) => onChange(item.id, { ...line, quantity: event.target.value })}
                />
              )}
              {(wholeOnly || line.quantity.trim() !== "") && (
                <SelectBox
                  label={t("orders.whatBecomes")}
                  value={line.disposition}
                  onChange={(event) => onChange(item.id, { ...line, disposition: event.target.value as Disposition })}
                >
                  <option value="RESTOCK">{t("orders.disposition.RESTOCK")}</option>
                  <option value="WRITE_OFF">{t("orders.disposition.WRITE_OFF")}</option>
                </SelectBox>
              )}
            </fieldset>
          );
        })}
    </>
  );
}

/** The lines someone filled in, or null when a number is impossible. */
function linesBack(items: OrderItem[], most: (item: OrderItem) => number, lines: Record<string, Coming>) {
  const back = [];
  for (const item of items) {
    const line = lines[item.id];
    if (line?.quantity.trim()) {
      const quantity = parseCount(line.quantity);
      if (quantity === null || quantity < 1 || quantity > most(item)) {
        return null;
      }
      back.push({ item, quantity, disposition: line.disposition });
    }
  }
  return back;
}

export function DeliverDialog({ order, onClose }: { order: Order; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [refused, setRefused] = useState<Record<string, Coming>>({});
  const [someRefused, setSomeRefused] = useState(false);
  const [collectedText, setCollectedText] = useState<string>();
  const [errors, setErrors] = useState<{ collected?: string; refused?: string }>({});
  const most = (item: OrderItem) => item.quantity;
  const shipment = order.shipments.findLast((one) => one.status === "IN_TRANSIT");
  const lines = linesBack(order.items, most, someRefused ? refused : {});
  // As the server works it out: what it was to collect, less what refused items took off.
  const credit = Math.min(
    (lines ?? []).reduce((sum, line) => sum + line.quantity * line.item.unitPrice, 0),
    order.subtotal - order.discount,
  );
  const expected = Math.min(shipment?.codExpected ?? 0, Math.max(0, order.balanceDue - credit));
  const shown = collectedText ?? takaInput(expected);
  const { change, problem } = useOrderChange(
    order,
    ({ codCollected, back }: { codCollected: number; back: NonNullable<typeof lines> }) =>
      call(
        api.POST("/api/v1/orders/{id}/deliver", {
          params: { path: { id: order.id } },
          body: {
            codCollected,
            refused: back.map((line) => ({
              orderItemId: line.item.id,
              quantity: line.quantity,
              disposition: line.disposition,
            })),
            reason: back.length > 0 ? t("orders.refusedReason") : undefined,
            version: order.version,
          },
        }),
      ),
    t("orders.moved.DELIVERED"),
    onClose,
  );
  function submit() {
    const codCollected = parseTaka(shown);
    const wrong = {
      collected: codCollected === null ? t("field.amountInvalid") : undefined,
      refused: lines === null ? t("field.countInvalid") : undefined,
    };
    setErrors(wrong);
    if (codCollected !== null && lines !== null) {
      change.mutate({ codCollected, back: lines });
    }
  }
  return (
    <OrderDialog
      title={t("orders.deliverTitle", { number: format.orderNumber(order.numberLabel) })}
      action={t("orders.deliver")}
      problem={problem}
      pending={change.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field
        label={t("orders.collected")}
        hint={t("orders.collectedHint", { amount: format.taka(expected) })}
        inputMode="decimal"
        value={shown}
        error={errors.collected}
        onChange={(event) => setCollectedText(event.target.value)}
      />
      <label className="flex min-h-11 items-center gap-2">
        <input
          type="checkbox"
          className="size-5 accent-primary"
          checked={someRefused}
          onChange={(event) => setSomeRefused(event.target.checked)}
        />
        {t("orders.someRefused")}
      </label>
      {someRefused && (
        <ItemsBack
          items={order.items}
          most={most}
          lines={refused}
          onChange={(id, line) => setRefused({ ...refused, [id]: line })}
        />
      )}
      {errors.refused && <p className="text-sm text-destructive">{errors.refused}</p>}
    </OrderDialog>
  );
}

/** A parcel that came back whole, or some or all of a delivered order sent back later. */
export function ReturnDialog({
  order,
  kind,
  onClose,
}: {
  order: Order;
  kind: "FAILED_DELIVERY" | "CUSTOMER_RETURN";
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const failed = kind === "FAILED_DELIVERY";
  const out = stillOut(order);
  const most = (item: OrderItem) => (failed ? item.quantity : (out.get(item.id) ?? 0));
  const [reason, setReason] = useState("");
  const [back, setBack] = useState<Record<string, Coming>>({});
  const [refundText, setRefundText] = useState<string>();
  const [refundMethod, setRefundMethod] = useState<PaymentMethod>("BKASH");
  const [reference, setReference] = useState("");
  const [errors, setErrors] = useState<{ reason?: string; items?: string; refund?: string }>({});
  const lines = failed
    ? order.items.map((item) => ({
        item,
        quantity: item.quantity,
        disposition: back[item.id]?.disposition ?? ("RESTOCK" as Disposition),
      }))
    : linesBack(order.items, most, back);
  // As the server works it out: goods no longer owed for, and so what has been overpaid.
  const value = (lines ?? []).reduce((sum, line) => sum + line.quantity * line.item.unitPrice, 0);
  const credit = Math.min(value, order.subtotal - order.discount - order.returnCredit);
  const refundable = failed ? 0 : Math.max(0, credit - order.balanceDue);
  const refundShown = refundText ?? (refundable > 0 ? takaInput(refundable) : "");
  const { change, problem } = useOrderChange(
    order,
    ({ refundAmount, items }: { refundAmount: number; items: NonNullable<typeof lines> }) =>
      call(
        api.POST("/api/v1/orders/{id}/returns", {
          params: { path: { id: order.id } },
          body: {
            kind,
            reason: reason.trim(),
            items: items.map((line) => ({
              orderItemId: line.item.id,
              quantity: line.quantity,
              disposition: line.disposition,
            })),
            refund:
              refundAmount > 0
                ? { amount: refundAmount, method: refundMethod, reference: reference.trim() || undefined }
                : undefined,
            version: order.version,
          },
        }),
      ),
    t(failed ? "orders.moved.RETURNED" : "orders.returnSaved"),
    onClose,
  );
  function submit() {
    const refundAmount = refundShown.trim() ? parseTaka(refundShown) : 0;
    const wrong = {
      reason: reason.trim() ? undefined : t("field.required"),
      items: lines === null || lines.length === 0 ? t("orders.chooseItemsBack") : undefined,
      refund: refundAmount === null ? t("field.amountInvalid") : undefined,
    };
    setErrors(wrong);
    if (!wrong.reason && lines && lines.length > 0 && refundAmount !== null) {
      change.mutate({ refundAmount, items: lines });
    }
  }
  return (
    <OrderDialog
      title={t(failed ? "orders.failedTitle" : "orders.returnTitle", { number: format.orderNumber(order.numberLabel) })}
      action={t(failed ? "orders.failedDelivery" : "orders.customerReturn")}
      problem={problem}
      pending={change.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field
        label={t("orders.reason")}
        maxLength={MAX_REASON}
        autoComplete="off"
        value={reason}
        error={errors.reason}
        onChange={(event) => setReason(event.target.value)}
      />
      <ItemsBack
        items={order.items}
        most={most}
        lines={back}
        wholeOnly={failed}
        onChange={(id, line) => setBack({ ...back, [id]: line })}
      />
      {errors.items && <p className="text-sm text-destructive">{errors.items}</p>}
      {refundable > 0 && (
        <>
          <Field
            label={t("orders.refund")}
            hint={t("orders.refundHint", { amount: format.taka(refundable) })}
            inputMode="decimal"
            value={refundShown}
            error={errors.refund}
            onChange={(event) => setRefundText(event.target.value)}
          />
          {refundShown.trim() !== "" && (
            <>
              <SelectBox
                label={t("orders.refundBy")}
                value={refundMethod}
                onChange={(event) => setRefundMethod(event.target.value as PaymentMethod)}
              >
                {PAYMENT_METHODS.map((method) => (
                  <option key={method} value={method}>
                    {t(`paymentMethod.${method}`)}
                  </option>
                ))}
              </SelectBox>
              <Field
                label={t("orders.reference")}
                autoComplete="off"
                maxLength={100}
                value={reference}
                onChange={(event) => setReference(event.target.value)}
              />
            </>
          )}
        </>
      )}
    </OrderDialog>
  );
}

export function PaymentDialog({ order, onClose }: { order: Order; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [amount, setAmount] = useState(takaInput(order.balanceDue));
  const [method, setMethod] = useState<PaymentMethod>("BKASH");
  const [reference, setReference] = useState("");
  const [error, setError] = useState<string>();
  // Kept for retries of this one payment, so a bad connection never records two.
  const idempotencyKey = useRef(newIdempotencyKey());
  const { change, problem } = useOrderChange(
    order,
    (poisha: number) =>
      call(
        api.POST("/api/v1/orders/{id}/payments", {
          params: { path: { id: order.id } },
          headers: { "Idempotency-Key": idempotencyKey.current },
          body: { method, amount: poisha, reference: reference.trim() || undefined },
        }),
      ).catch((failure: unknown) => {
        // The server answered: this attempt is finished, so a new one is a new payment.
        if (failure instanceof ApiError) {
          idempotencyKey.current = newIdempotencyKey();
        }
        throw failure;
      }),
    t("orders.paymentSaved"),
    onClose,
  );
  function submit() {
    const poisha = parseTaka(amount);
    setError(poisha === null || poisha === 0 ? t("field.amountInvalid") : undefined);
    if (poisha) {
      change.mutate(poisha);
    }
  }
  return (
    <OrderDialog
      title={t("orders.addPayment")}
      action={t("orders.savePayment")}
      problem={problem}
      pending={change.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field
        label={t("orders.amount")}
        hint={t("orders.stillOwed", { amount: format.taka(order.balanceDue) })}
        inputMode="decimal"
        value={amount}
        error={error}
        onChange={(event) => setAmount(event.target.value)}
      />
      <SelectBox
        label={t("orders.paidBy")}
        value={method}
        onChange={(event) => setMethod(event.target.value as PaymentMethod)}
      >
        {PAYMENT_METHODS.map((one) => (
          <option key={one} value={one}>
            {t(`paymentMethod.${one}`)}
          </option>
        ))}
      </SelectBox>
      <Field
        label={t("orders.reference")}
        autoComplete="off"
        maxLength={100}
        value={reference}
        onChange={(event) => setReference(event.target.value)}
      />
    </OrderDialog>
  );
}

export function CancelDialog({ order, onClose }: { order: Order; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string>();
  const { change, problem } = useOrderChange(
    order,
    (why: string) =>
      call(
        api.POST("/api/v1/orders/{id}/cancel", {
          params: { path: { id: order.id } },
          body: { reason: why, version: order.version },
        }),
      ),
    t("orders.moved.CANCELLED"),
    onClose,
  );
  function submit() {
    const why = reason.trim();
    setError(why ? undefined : t("field.required"));
    if (why) {
      change.mutate(why);
    }
  }
  return (
    <OrderDialog
      title={t("orders.cancelTitle", { number: format.orderNumber(order.numberLabel) })}
      action={t("orders.cancel")}
      problem={problem}
      pending={change.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <AreaField
        label={t("orders.reason")}
        maxLength={MAX_REASON}
        value={reason}
        error={error}
        onChange={(event) => setReason(event.target.value)}
      />
    </OrderDialog>
  );
}
