import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import { Field } from "@/components/form/Field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { useFormat } from "@/lib/format";
import { parseTaka } from "@/lib/money";
import type { BulkResult, OrderSummary } from "./api";
import { CourierField } from "./CourierField";

/** One courier for all, each parcel with its own tracking number. Each order goes separately. */
export function BulkShipDialog({
  orders,
  onDone,
  onClose,
}: {
  orders: OrderSummary[];
  onDone: (results: BulkResult[]) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const [courier, setCourier] = useState("");
  const [charge, setCharge] = useState("");
  const [tracking, setTracking] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<{ courier?: string; charge?: string }>({});
  const [problem, setProblem] = useState<string>();
  const ship = useMutation({
    mutationFn: (courierCharge: number | undefined) =>
      call(
        api.POST("/api/v1/orders/bulk/ship", {
          body: {
            courier: courier.trim(),
            courierCharge,
            orders: orders.map((order) => ({
              orderId: order.id,
              trackingNo: tracking[order.id]?.trim() || undefined,
            })),
          },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: onDone,
    onError: (error) => setProblem(errorText(error)),
  });
  function submit() {
    const courierCharge = charge.trim() ? parseTaka(charge) : undefined;
    const wrong = {
      courier: courier.trim() ? undefined : t("field.required"),
      charge: courierCharge === null ? t("field.amountInvalid") : undefined,
    };
    setErrors(wrong);
    if (!wrong.courier && courierCharge !== null) {
      ship.mutate(courierCharge);
    }
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("orders.bulkShipTitle", { count: format.number(orders.length) })}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          {problem && (
            <Alert variant="destructive">
              <AlertDescription>{problem}</AlertDescription>
            </Alert>
          )}
          <CourierField value={courier} onChange={setCourier} error={errors.courier} />
          <Field
            label={t("orders.courierChargeEach")}
            hint={t("orders.courierChargeHint")}
            inputMode="decimal"
            value={charge}
            error={errors.charge}
            onChange={(event) => setCharge(event.target.value)}
          />
          {orders.map((order) => (
            <Field
              key={order.id}
              label={t("orders.trackingOf", {
                number: format.orderNumber(order.numberLabel),
                name: order.customerName,
              })}
              autoComplete="off"
              maxLength={100}
              value={tracking[order.id] ?? ""}
              onChange={(event) => setTracking({ ...tracking, [order.id]: event.target.value })}
            />
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("page.cancel")}
          </Button>
          <Button disabled={ship.isPending} onClick={submit}>
            {t("orders.ship")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
