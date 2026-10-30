import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { errorMessages } from "@/api/query";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { useFormat } from "@/lib/format";
import { billingChanged, type PayBy, type Plan, quoteQuery, usePlanName } from "./api";

const MONTHS = [1, 6, 12] as const;

/**
 * Paying for a plan: some months of it, or (`upgrade`) the difference for the rest of the running
 * period. The total is the server's own quote, so it is what will be charged.
 */
export function PayDialog({ plan, upgrade, onClose }: { plan: Plan; upgrade: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const id = useId();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [months, setMonths] = useState<(typeof MONTHS)[number]>(1);
  const [payBy, setPayBy] = useState<PayBy>("SSLCOMMERZ");
  const quote = useQuery(quoteQuery(plan.code, upgrade ? undefined : months));
  const pay = useMutation({
    mutationFn: () =>
      call(
        upgrade
          ? api.POST("/api/v1/billing/upgrade", { body: { planCode: plan.code, payBy } })
          : api.POST("/api/v1/billing/checkout", { body: { planCode: plan.code, months, payBy } }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: async (checkout) => {
      billingChanged(queryClient);
      if (checkout.gatewayUrl) {
        // Off to the gateway's page, which comes back to /billing/return.
        window.location.assign(checkout.gatewayUrl);
      } else {
        await navigate({ to: "/billing/invoices/$invoiceId", params: { invoiceId: checkout.invoiceId } });
      }
    },
  });
  const problem = pay.error ?? quote.error;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-dvh overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {t(upgrade ? "billing.upgradeTitle" : "billing.payTitle", { plan: planName(plan) })}
          </DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          {problem && (
            <Alert variant="destructive">
              <AlertDescription>{errorMessages(problem)}</AlertDescription>
            </Alert>
          )}
          {upgrade ? (
            <p>{t("billing.upgradeHint")}</p>
          ) : (
            <fieldset className="flex flex-col gap-1">
              <legend className="mb-1 text-[15px] font-semibold">{t("billing.forHowLong")}</legend>
              {MONTHS.map((option) => (
                <Label key={option} className="min-h-11 text-base font-normal">
                  <input
                    type="radio"
                    name={`${id}-months`}
                    className="size-5 accent-primary"
                    checked={months === option}
                    onChange={() => setMonths(option)}
                  />
                  {t(`billing.months.${option}`)}
                </Label>
              ))}
            </fieldset>
          )}
          <fieldset className="flex flex-col gap-1">
            <legend className="mb-1 text-[15px] font-semibold">{t("billing.payHow")}</legend>
            {(["SSLCOMMERZ", "BANK_TRANSFER"] as const).map((option) => (
              <Label key={option} className="min-h-11 text-base font-normal">
                <input
                  type="radio"
                  name={`${id}-pay-by`}
                  className="size-5 accent-primary"
                  checked={payBy === option}
                  onChange={() => setPayBy(option)}
                />
                {t(`billing.payBy.${option}`)}
              </Label>
            ))}
          </fieldset>
          {quote.data && (
            <div role="status" className="border-t pt-3">
              <p className="flex justify-between gap-3 text-lg font-bold">
                <span>{t("billing.total")}</span>
                <span>{format.taka(quote.data.amount)}</span>
              </p>
              {quote.data.vatAmount > 0 && (
                <p className="text-sm text-muted-foreground">
                  {t("billing.vatIncluded", { amount: format.taka(quote.data.vatAmount) })}
                </p>
              )}
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("page.cancel")}
          </Button>
          <Button disabled={pay.isPending || !quote.data} onClick={() => pay.mutate()}>
            {t(payBy === "SSLCOMMERZ" ? "billing.goToPayment" : "billing.getInstructions")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
