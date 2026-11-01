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
import { parseTaka, takaInput } from "@/lib/money";
import { type Order, orderChanged, orderErrorText, PAYMENT_METHODS, type PaymentMethod } from "./api";
import { CourierField } from "./CourierField";

const MAX_REASON = 500;

/** A change made in a dialog: the answer updates the page; a refusal stays in the dialog, with the order read again. */
export function useOrderChange<V>(
  order: Order,
  send: (variables: V) => Promise<Order>,
  doneText: string,
  onDone: () => void,
) {
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

export const lineName = (item: { productName: string; variantLabel: string }) =>
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
