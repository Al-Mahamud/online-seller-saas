import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { billingChanged, invoiceQuery } from "./api";

const route = getRouteApi("/_app/billing/return");
const POLL_EVERY = 3_000;
const STOP_AFTER = 5 * 60_000;
const SAY_SLOW_AFTER = 60_000;

/**
 * Where the gateway sends the browser back to. That return proves nothing: only the gateway's
 * validated answer pays an invoice, so this asks the server until it has.
 */
export function PaymentReturnPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const { result, invoice: invoiceId } = route.useSearch();
  const queryClient = useQueryClient();
  const started = useRef(Date.now());
  const waiting = result === "success";
  const { data: invoice } = useQuery({
    ...invoiceQuery(invoiceId ?? ""),
    enabled: !!invoiceId,
    refetchInterval: (query) =>
      waiting && query.state.data?.status !== "PAID" && Date.now() - started.current < STOP_AFTER ? POLL_EVERY : false,
  });
  const paid = invoice?.status === "PAID";
  useEffect(() => {
    if (paid) {
      billingChanged(queryClient);
    }
  }, [paid, queryClient]);
  const slow = Date.now() - started.current > SAY_SLOW_AFTER;
  return (
    <div className="flex max-w-xl flex-col gap-4">
      <h1 className="text-2xl font-bold tracking-tight md:text-3xl">{t("billing.return.title")}</h1>
      {paid && invoice && (
        <Alert>
          <AlertDescription>
            <p className="font-semibold">{t("billing.return.paid", { amount: format.taka(invoice.amount) })}</p>
            {invoice.periodEnd && <p>{t("billing.paidUntil", { date: format.date(invoice.periodEnd) })}</p>}
          </AlertDescription>
        </Alert>
      )}
      {!paid && waiting && (
        <p role="status" className="rounded-lg bg-muted p-3">
          {t(slow ? "billing.return.slow" : "billing.return.waiting")}
        </p>
      )}
      {!paid && !waiting && (
        <Alert variant="destructive">
          <AlertDescription>
            {t(result === "cancel" ? "billing.return.cancelled" : "billing.return.failed")}
          </AlertDescription>
        </Alert>
      )}
      <Button asChild variant={paid ? "default" : "outline"} className="self-start">
        <Link to="/billing">{t("billing.return.back")}</Link>
      </Button>
    </div>
  );
}
