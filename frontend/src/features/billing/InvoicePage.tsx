import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Check, ChevronLeft, Printer } from "lucide-react";
import { type ReactNode, useState } from "react";
import { useTranslation } from "react-i18next";
import { useBusiness } from "@/api/business";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { invoicePrintUrl, invoiceQuery, plansQuery, usePlanName } from "./api";

const route = getRouteApi("/_app/billing/invoices/$invoiceId");

function Step({
  number,
  done,
  title,
  children,
}: {
  number: string;
  done?: boolean;
  title: string;
  children: ReactNode;
}) {
  return (
    <li className="flex gap-3">
      <span
        className={cn(
          "flex size-7 flex-none items-center justify-center rounded-full font-bold",
          done ? "bg-accent text-accent-foreground" : "bg-ledger text-marigold-text",
        )}
      >
        {number}
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-bold">{title}</p>
        {children}
      </div>
    </li>
  );
}

/** One invoice, as a receipt: emails link here. While a bank transfer is awaited, it says how to pay. */
export function InvoicePage() {
  const { t } = useTranslation();
  const format = useFormat();
  const planName = usePlanName();
  const business = useBusiness();
  const { invoiceId } = route.useParams();
  const { data: invoice } = useQuery(invoiceQuery(invoiceId));
  const { data: plans } = useQuery(plansQuery);
  const [copied, setCopied] = useState(false);
  const back = (
    <Link
      to="/billing"
      className="-ml-1 flex min-h-11 w-fit items-center gap-1 font-semibold text-primary no-underline"
    >
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("billing.title")}
    </Link>
  );
  if (!invoice) {
    return back;
  }
  const plan = plans?.find((one) => one.code === invoice.planCode);
  const name = plan ? planName(plan) : invoice.planCode;
  const paid = invoice.status === "PAID";
  const open = invoice.status === "OPEN";
  const how = t(`billing.how.${invoice.payBy}` as "billing.how.SSLCOMMERZ");
  const what = t(`billing.what.${invoice.kind}` as "billing.what.SUBSCRIPTION", {
    plan: name,
    months: format.number(invoice.months),
  });
  const badge = paid
    ? "bg-accent text-accent-foreground"
    : open
      ? "bg-warning text-warning-foreground"
      : "bg-muted text-muted-foreground";
  const rows: [string, string][] = [
    [t("billing.business"), business.name],
    [t("billing.plan"), what],
  ];
  if (invoice.periodStart && invoice.periodEnd) {
    rows.push([
      t("billing.period"),
      t("billing.periodRange", { from: format.date(invoice.periodStart), to: format.date(invoice.periodEnd) }),
    ]);
  }
  if (invoice.vatAmount > 0) {
    rows.push([t("billing.vat"), format.taka(invoice.vatAmount)]);
  }
  return (
    <div className="flex w-full max-w-2xl flex-col gap-4">
      {back}
      <article aria-labelledby="invoice-number" className="overflow-hidden rounded-2xl border bg-card">
        <header className="relative flex flex-col gap-2 bg-ledger py-4.5 pr-5 pl-12 text-white">
          <div aria-hidden="true" className="absolute inset-y-0 left-6 w-0.5 bg-marigold" />
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h1 id="invoice-number" className="text-2xl font-bold">
              {invoice.number}
            </h1>
            <span className={cn("flex items-center gap-1.5 rounded-full px-3 py-0.5 text-sm font-bold", badge)}>
              {paid && <Check aria-hidden="true" className="size-3.5" strokeWidth={3} />}
              {t(`billing.invoiceStatus.${invoice.status}` as "billing.invoiceStatus.PAID")}
            </span>
          </div>
          <p className="text-[34px] leading-tight font-bold text-marigold-text">{format.taka(invoice.amount)}</p>
          <p className="text-[15px] text-ledger-text">
            {paid && invoice.paidAt
              ? t("billing.paidOn", { date: format.date(invoice.paidAt), how })
              : `${t("billing.openSince", { date: format.date(invoice.createdAt) })} · ${how}`}
          </p>
        </header>
        <dl className="flex flex-col px-5 pt-2 pb-4 text-[15px]">
          {rows.map(([label, value]) => (
            <div key={label} className="flex justify-between gap-3 border-b border-muted py-2.5">
              <dt className="text-muted-foreground">{label}</dt>
              <dd className="text-right font-semibold break-words">{value}</dd>
            </div>
          ))}
          <div className="flex justify-between gap-3 pt-3 text-[19px] font-bold">
            <dt>{t("billing.totalWithVat")}</dt>
            <dd>{format.taka(invoice.amount)}</dd>
          </div>
        </dl>
      </article>

      {open && invoice.bankTransfer && (
        <section
          aria-label={t("billing.transferTitle", { amount: format.taka(invoice.amount) })}
          className="rounded-2xl border bg-card p-5"
        >
          <ol className="flex flex-col gap-4">
            <Step number={format.number(1)} title={t("billing.step1", { amount: format.taka(invoice.amount) })}>
              <div className="mt-1.5 flex items-start justify-between gap-2.5 rounded-xl bg-background p-3 text-[15px] leading-relaxed">
                <p className="min-w-0 whitespace-pre-wrap break-words">{invoice.bankTransfer}</p>
                <button
                  type="button"
                  className="h-10 flex-none cursor-pointer rounded-[10px] border-[1.5px] border-input bg-card px-3 text-sm font-bold text-primary hover:bg-muted"
                  onClick={() => {
                    void navigator.clipboard?.writeText(invoice.bankTransfer ?? "").then(() => setCopied(true));
                  }}
                >
                  {copied ? t("billing.copied") : t("billing.copy")}
                </button>
              </div>
            </Step>
            <Step number={format.number(2)} title={t("billing.step2")}>
              <p className="text-[15px] text-muted-foreground">{t("billing.step2Hint", { number: invoice.number })}</p>
            </Step>
            <Step number={format.number(3)} done title={t("billing.step3")}>
              <p className="text-[15px] text-muted-foreground">{t("billing.step3Hint")}</p>
            </Step>
          </ol>
          <p className="mt-4 rounded-xl bg-accent px-3.5 py-3 text-[15px] text-accent-foreground">
            {t("billing.payOnlineInstead")}
          </p>
        </section>
      )}
      {open && !invoice.bankTransfer && (
        <p
          role="status"
          className="rounded-xl bg-warning px-3.5 py-3 text-[15px] font-semibold text-warning-foreground"
        >
          {t("billing.gatewayNotDone")}
        </p>
      )}

      <Button asChild variant="outline" className="h-11.5 self-start rounded-xl border-[1.5px] font-bold">
        <a href={invoicePrintUrl(invoice.id)} target="_blank" rel="noopener">
          <Printer aria-hidden="true" />
          {t("billing.printOrPdf")}
        </a>
      </Button>
    </div>
  );
}
