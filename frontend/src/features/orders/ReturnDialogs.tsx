import { Minus, Plus } from "lucide-react";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { Field, SelectBox } from "@/components/form/Field";
import { LedgerDialog } from "@/components/layout/LedgerDialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { parseTaka, takaInput } from "@/lib/money";
import { cn } from "@/lib/utils";
import { type Disposition, type Order, type OrderItem, PAYMENT_METHODS, type PaymentMethod, stillOut } from "./api";
import { lineName, useOrderChange } from "./OrderDialogs";

const MAX_REASON = 500;
const DISPOSITIONS: Disposition[] = ["RESTOCK", "WRITE_OFF"];

interface Coming {
  quantity: number;
  disposition: Disposition;
}

const NOT_COMING: Coming = { quantity: 0, disposition: "RESTOCK" };

/** The items chosen, with what becomes of each. The most can have dropped if the order changed meanwhile. */
function linesBack(items: OrderItem[], most: (item: OrderItem) => number, lines: Record<string, Coming>) {
  return items.flatMap((item) => {
    const line = lines[item.id];
    const quantity = Math.min(line?.quantity ?? 0, most(item));
    return line && quantity > 0 ? [{ item, quantity, disposition: line.disposition }] : [];
  });
}

const courierOf = (order: Order) => {
  const shipment = order.shipments.findLast((one) => one.status === "IN_TRANSIT");
  return [shipment?.courier, shipment?.trackingNo].filter(Boolean).join(" ");
};

const joined = (...parts: (string | false | null | undefined)[]) => parts.filter(Boolean).join(" · ");

/** One pair of buttons around a count. It stays focusable at its limits, so a keyboard doesn't lose its place. */
function CountStepper({
  label,
  count,
  most,
  onChange,
}: {
  label: string;
  count: number;
  most: number;
  onChange: (count: number) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const stepClass = "size-11 flex-none rounded-[10px] border-[1.5px] aria-disabled:opacity-50";
  return (
    <fieldset className="min-w-0 flex-none">
      <legend className="mb-0.75 text-[13px] font-semibold text-muted-foreground">{label}</legend>
      <div className="flex gap-1">
        <Button
          type="button"
          variant="outline"
          size="icon"
          className={stepClass}
          aria-label={t("orders.fewer")}
          aria-disabled={count <= 0 || undefined}
          onClick={() => count > 0 && onChange(count - 1)}
        >
          <Minus aria-hidden="true" />
        </Button>
        <output className="flex h-11 w-11.5 items-center justify-center rounded-[10px] border-[1.5px] border-input bg-card font-bold">
          {format.number(count)}
        </output>
        <Button
          type="button"
          variant="outline"
          size="icon"
          className={stepClass}
          aria-label={t("orders.more")}
          aria-disabled={count >= most || undefined}
          onClick={() => count < most && onChange(count + 1)}
        >
          <Plus aria-hidden="true" />
        </Button>
      </div>
    </fieldset>
  );
}

function DispositionChoice({ value, onChange }: { value: Disposition; onChange: (value: Disposition) => void }) {
  const { t } = useTranslation();
  const id = useId();
  return (
    <div role="radiogroup" aria-labelledby={id} className="flex flex-wrap items-center gap-2">
      <span id={id} className="text-sm font-semibold">
        {t("orders.whatBecomes")}
      </span>
      {DISPOSITIONS.map((option) => (
        <Label
          key={option}
          className={cn(
            "relative flex min-h-11 cursor-pointer items-center rounded-[10px] border-[1.5px] px-3 text-[15px] font-semibold has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
            value === option ? "border-primary bg-accent" : "border-input bg-card",
          )}
        >
          <input
            type="radio"
            name={id}
            className="absolute size-px opacity-0"
            checked={value === option}
            onChange={() => onChange(option)}
          />
          {t(`orders.disposition.${option}`)}
        </Label>
      ))}
    </div>
  );
}

/** Each item that can come back: how many, unless the whole parcel did, and what becomes of them. */
function ItemsBack({
  items,
  most,
  lines,
  countLabel,
  meta,
  wholeOnly,
  onChange,
}: {
  items: OrderItem[];
  most: (item: OrderItem) => number;
  lines: Record<string, Coming>;
  countLabel?: string;
  meta: (item: OrderItem) => string;
  wholeOnly?: boolean;
  onChange: (id: string, line: Coming) => void;
}) {
  return (
    <div>
      {items
        .filter((item) => most(item) > 0)
        .map((item) => {
          const line = lines[item.id] ?? NOT_COMING;
          return (
            <fieldset key={item.id} aria-label={lineName(item)} className="flex min-w-0 flex-col gap-2.5 border-t py-3">
              <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
                <div className="min-w-0 flex-[1_1_170px]">
                  <p className="font-semibold break-words">{item.productName}</p>
                  <p className="text-sm break-words text-muted-foreground">{meta(item)}</p>
                </div>
                {!wholeOnly && countLabel && (
                  <CountStepper
                    label={countLabel}
                    count={Math.min(line.quantity, most(item))}
                    most={most(item)}
                    onChange={(quantity) => onChange(item.id, { ...line, quantity })}
                  />
                )}
              </div>
              {(wholeOnly || line.quantity > 0) && (
                <DispositionChoice
                  value={line.disposition}
                  onChange={(disposition) => onChange(item.id, { ...line, disposition })}
                />
              )}
            </fieldset>
          );
        })}
    </div>
  );
}

function MoneyRow({ label, amount, strong }: { label: string; amount: string; strong?: boolean }) {
  return (
    <p className={cn("flex justify-between gap-3", strong ? "font-bold" : "text-[15px]")}>
      <span>{label}</span>
      <span className="flex-none">{amount}</span>
    </p>
  );
}

function MoneyBox({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation();
  const id = useId();
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2.5 rounded-xl bg-muted px-3.5 pt-3 pb-3.5">
      <h2 id={id} className="text-[15px] font-semibold">
        {t("orders.moneyTitle")}
      </h2>
      {children}
    </section>
  );
}

const moneyInput = "h-13 rounded-[10px] border-[1.5px] bg-card text-xl font-bold md:h-13 md:text-xl";

export function DeliverDialog({ order, onClose }: { order: Order; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const id = useId();
  const [refused, setRefused] = useState<Record<string, Coming>>({});
  const [someRefused, setSomeRefused] = useState(false);
  const [collectedText, setCollectedText] = useState<string>();
  const [errors, setErrors] = useState<{ collected?: string; refused?: string }>({});
  const most = (item: OrderItem) => item.quantity;
  const shipment = order.shipments.findLast((one) => one.status === "IN_TRANSIT");
  const lines = someRefused ? linesBack(order.items, most, refused) : [];
  // As the server works it out: what it was to collect, less what refused items took off.
  const credit = Math.min(
    lines.reduce((sum, line) => sum + line.quantity * line.item.unitPrice, 0),
    order.subtotal - order.discount,
  );
  const wasExpected = Math.min(shipment?.codExpected ?? 0, order.balanceDue);
  const expected = Math.min(shipment?.codExpected ?? 0, Math.max(0, order.balanceDue - credit));
  const shown = collectedText ?? takaInput(expected);
  const collected = parseTaka(shown);
  const short = collected === null ? 0 : expected - collected;
  const refusedCount = lines.reduce((sum, line) => sum + line.quantity, 0);
  const restocked = lines.reduce((sum, line) => sum + (line.disposition === "RESTOCK" ? line.quantity : 0), 0);
  const { change, problem } = useOrderChange(
    order,
    ({ codCollected, back }: { codCollected: number; back: typeof lines }) =>
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
    const wrong = {
      collected: collected === null ? t("field.amountInvalid") : undefined,
      // "Some were refused" with none chosen would quietly record that every item was taken.
      refused: someRefused && lines.length === 0 ? t("orders.chooseItemsRefused") : undefined,
    };
    setErrors(wrong);
    if (collected !== null && !wrong.refused) {
      change.mutate({ codCollected: collected, back: lines });
    }
  }
  const choices = [
    { refused: false, label: t("orders.tookAllYes") },
    { refused: true, label: t("orders.tookAllNo") },
  ];
  return (
    <LedgerDialog
      title={t("orders.deliverTitle", { number: format.orderNumber(order.numberLabel) })}
      description={joined(order.customer.name, courierOf(order))}
      problem={errors.refused ?? problem}
      summary={
        refusedCount > 0
          ? t("orders.refusedSummary", {
              count: refusedCount,
              items: format.number(refusedCount),
              restock: format.number(restocked),
            })
          : t("orders.allDelivered")
      }
      action={t("orders.deliver")}
      pending={change.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <fieldset className="min-w-0">
        <legend className="mb-2 text-[15px] font-semibold">{t("orders.tookAll")}</legend>
        <div className="grid gap-2.5 md:grid-cols-2">
          {choices.map((choice) => (
            <Label
              key={String(choice.refused)}
              className={cn(
                "flex min-h-14.5 cursor-pointer items-center gap-2.5 rounded-xl border-[1.5px] px-3 py-2 text-base font-semibold",
                someRefused === choice.refused ? "border-primary bg-accent" : "border-input bg-card",
              )}
            >
              <input
                type="radio"
                name={id}
                className="size-5 flex-none accent-primary"
                checked={someRefused === choice.refused}
                onChange={() => {
                  setSomeRefused(choice.refused);
                  setErrors({ ...errors, refused: undefined });
                }}
              />
              {choice.label}
            </Label>
          ))}
        </div>
      </fieldset>

      {someRefused && (
        <ItemsBack
          items={order.items}
          most={most}
          lines={refused}
          countLabel={t("orders.howManyRefused")}
          meta={(item) =>
            joined(
              item.variantLabel,
              t("orders.inOrder", { howMany: format.number(item.quantity) }),
              format.taka(item.unitPrice),
            )
          }
          onChange={(itemId, line) => {
            setRefused({ ...refused, [itemId]: line });
            setErrors({ ...errors, refused: undefined });
          }}
        />
      )}

      <MoneyBox>
        {wasExpected > expected && (
          <>
            <MoneyRow label={t("orders.wasToCollect")} amount={format.taka(wasExpected)} />
            <MoneyRow label={t("orders.refusedOff")} amount={`−${format.taka(wasExpected - expected)}`} />
          </>
        )}
        <MoneyRow label={t("orders.nowToCollect")} amount={format.taka(expected)} strong />
        <Field
          label={t("orders.collected")}
          inputMode="decimal"
          className={moneyInput}
          value={shown}
          error={errors.collected}
          onChange={(event) => setCollectedText(event.target.value)}
        />
        {short !== 0 && (
          <p className="text-sm font-semibold text-warning-foreground">
            {t(short > 0 ? "orders.collectedLess" : "orders.collectedMore", { amount: format.taka(Math.abs(short)) })}
          </p>
        )}
      </MoneyBox>
    </LedgerDialog>
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
  const value = lines.reduce((sum, line) => sum + line.quantity * line.item.unitPrice, 0);
  const credit = Math.min(value, order.subtotal - order.discount - order.returnCredit);
  const refundable = failed ? 0 : Math.max(0, credit - order.balanceDue);
  const refundShown = refundText ?? (refundable > 0 ? takaInput(refundable) : "");
  const coming = lines.reduce((sum, line) => sum + line.quantity, 0);
  const restocked = lines.reduce((sum, line) => sum + (line.disposition === "RESTOCK" ? line.quantity : 0), 0);
  const { change, problem } = useOrderChange(
    order,
    ({ refundAmount, items }: { refundAmount: number; items: typeof lines }) =>
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
    // A refund typed before the items changed doesn't count once its box has gone.
    const refundAmount = refundable > 0 && refundShown.trim() ? parseTaka(refundShown) : 0;
    const wrong = {
      reason: reason.trim() ? undefined : t("field.required"),
      items: lines.length === 0 ? t("orders.chooseItemsBack") : undefined,
      refund: refundAmount === null ? t("field.amountInvalid") : undefined,
    };
    setErrors(wrong);
    if (!wrong.reason && !wrong.items && refundAmount !== null) {
      change.mutate({ refundAmount, items: lines });
    }
  }
  const summary = failed
    ? t("orders.parcelSummary", { restock: format.number(restocked) })
    : coming > 0
      ? t("orders.backSummary", { count: coming, items: format.number(coming), restock: format.number(restocked) })
      : t("orders.noneChosen");
  return (
    <LedgerDialog
      title={t(failed ? "orders.failedTitle" : "orders.returnTitle", { number: format.orderNumber(order.numberLabel) })}
      description={
        failed
          ? joined(t("orders.wholeParcel"), courierOf(order))
          : joined(
              order.customer.name,
              order.deliveredAt && t("orders.deliveredOn", { date: format.date(order.deliveredAt) }),
            )
      }
      problem={errors.items ?? problem}
      summary={summary}
      action={t(failed ? "orders.failedDelivery" : "orders.customerReturn")}
      pending={change.isPending}
      onSubmit={submit}
      onClose={onClose}
    >
      <section>
        <h2 className="mb-1 text-[15px] font-semibold">{t(failed ? "orders.whichFate" : "orders.whichBack")}</h2>
        <ItemsBack
          items={order.items}
          most={most}
          lines={back}
          countLabel={t("orders.howMany")}
          wholeOnly={failed}
          meta={(item) =>
            joined(
              item.variantLabel,
              t(failed ? "orders.inParcel" : "orders.taken", { howMany: format.number(most(item)) }),
              !failed && format.taka(item.unitPrice),
            )
          }
          onChange={(itemId, line) => {
            setBack({ ...back, [itemId]: line });
            setErrors({ ...errors, items: undefined });
          }}
        />
      </section>

      <Field
        label={t("orders.reason")}
        maxLength={MAX_REASON}
        autoComplete="off"
        placeholder={t(failed ? "orders.failedReasonHint" : "orders.returnReasonHint")}
        className="h-12 rounded-[10px] border-[1.5px] text-base md:h-11"
        value={reason}
        error={errors.reason}
        onChange={(event) => setReason(event.target.value)}
      />

      {!failed && coming > 0 && (
        <MoneyBox>
          <MoneyRow label={t("orders.backValue")} amount={format.taka(value)} strong />
          {refundable > 0 && (
            <>
              <Field
                label={t("orders.refund")}
                hint={t("orders.refundHint", { amount: format.taka(refundable) })}
                inputMode="decimal"
                className={moneyInput}
                value={refundShown}
                error={errors.refund}
                onChange={(event) => setRefundText(event.target.value)}
              />
              {refundShown.trim() !== "" && (
                <div className="grid gap-2.5 md:grid-cols-2">
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
                    className="bg-card"
                    value={reference}
                    onChange={(event) => setReference(event.target.value)}
                  />
                </div>
              )}
            </>
          )}
        </MoneyBox>
      )}
    </LedgerDialog>
  );
}
