import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { ChevronLeft, Printer } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Section } from "@/components/layout/Section";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { invoicePrintUrl, invoiceQuery, plansQuery, usePlanName } from "./api";

const route = getRouteApi("/_app/billing/invoices/$invoiceId");

/** One invoice: emails link here. While a bank transfer is awaited, it says how to pay. */
export function InvoicePage() {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const { invoiceId } = route.useParams();
  const { data: invoice } = useQuery(invoiceQuery(invoiceId));
  const { data: plans } = useQuery(plansQuery);
  const back = (
    <Link to="/billing" className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline">
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("billing.title")}
    </Link>
  );
  if (!invoice) {
    return back;
  }
  const plan = plans?.find((one) => one.code === invoice.planCode);
  const open = invoice.status === "OPEN";
  return (
    <div className="flex max-w-2xl flex-col gap-4">
      <div>
        {back}
        <h1 className="text-2xl font-bold tracking-tight md:text-3xl">{invoice.number}</h1>
        <p className="text-muted-foreground">
          {t(`billing.invoiceStatus.${invoice.status}` as "billing.invoiceStatus.PAID")} ·{" "}
          {format.date(invoice.paidAt ?? invoice.createdAt)}
        </p>
      </div>
      {invoice.bankTransfer && (
        <Alert>
          <AlertDescription>
            <p className="font-semibold">{t("billing.transferTitle", { amount: format.taka(invoice.amount) })}</p>
            <p className="whitespace-pre-wrap">{invoice.bankTransfer}</p>
            <p className="mt-2">{t("billing.transferNext")}</p>
          </AlertDescription>
        </Alert>
      )}
      {open && !invoice.bankTransfer && (
        <Alert>
          <AlertDescription>{t("billing.gatewayNotDone")}</AlertDescription>
        </Alert>
      )}
      <Section title={t("billing.invoiceDetails")}>
        <dl className="flex flex-col gap-1">
          {plan && (
            <div className="flex justify-between gap-3">
              <dt>{t("billing.plan")}</dt>
              <dd>{planName(plan)}</dd>
            </div>
          )}
          {invoice.periodStart && invoice.periodEnd && (
            <div className="flex justify-between gap-3">
              <dt>{t("billing.period")}</dt>
              <dd className="text-right">
                {format.date(invoice.periodStart)} – {format.date(invoice.periodEnd)}
              </dd>
            </div>
          )}
          {invoice.vatAmount > 0 && (
            <div className="flex justify-between gap-3">
              <dt>{t("billing.vat")}</dt>
              <dd>{format.taka(invoice.vatAmount)}</dd>
            </div>
          )}
          <div className="flex justify-between gap-3 border-t pt-2 font-bold">
            <dt>{t("billing.total")}</dt>
            <dd>{format.taka(invoice.amount)}</dd>
          </div>
        </dl>
      </Section>
      <Button asChild variant="outline" className="self-start">
        <a href={invoicePrintUrl(invoice.id)} target="_blank" rel="noopener">
          <Printer aria-hidden="true" />
          {t("billing.print")}
        </a>
      </Button>
    </div>
  );
}
