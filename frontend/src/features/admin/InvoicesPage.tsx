import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Download } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { AddressPagination } from "@/components/layout/Pagination";
import { dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { AdminDialog } from "./AdminDialog";
import { AdminCard, AdminHero, HeroFigure, usePlanNames } from "./AdminParts";
import { type AdminInvoice, downloadLedger, metricsQuery, transfersQuery } from "./api";
import { useAdminTexts } from "./texts";

const route = getRouteApi("/admin/invoices");

const DAY = 24 * 60 * 60 * 1000;

/** Bank transfers to be seen arriving (or already seen), and the month's ledger for the accountant. */
export function InvoicesPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const planName = usePlanNames();
  const { tab, ...paging } = route.useSearch();
  const navigate = route.useNavigate();
  const paid = tab === "paid";
  const { data, isSuccess } = useQuery(transfersQuery(paid ? "PAID" : "OPEN", paging));
  const { data: metrics } = useQuery(metricsQuery);
  const rows = data?.items ?? [];
  const [marking, setMarking] = useState<AdminInvoice>();
  const [month, setMonth] = useState(dhakaToday().slice(0, 7));
  const download = useMutation({ mutationFn: () => downloadLedger(month) });
  const what = (invoice: AdminInvoice) =>
    invoice.kind === "UPGRADE"
      ? text.invoice.upgrade(planName(invoice.planCode))
      : text.invoice.plan(planName(invoice.planCode), format.number(invoice.months));
  const tabs = [
    {
      id: undefined,
      label: metrics ? `${text.invoices.waiting} ${format.number(metrics.waitingTransfers)}` : text.invoices.waiting,
    },
    { id: "paid" as const, label: text.invoices.paid },
  ];
  return (
    <div className="flex flex-col gap-4">
      <AdminHero
        title={text.nav.invoices}
        subtitle={text.invoices.hint}
        aside={
          metrics && (
            <dl>
              <HeroFigure
                label={text.invoices.waiting}
                value={text.invoices.waitingTotal(
                  format.number(metrics.waitingTransfers),
                  format.taka(metrics.waitingPoisha),
                )}
                gold
              />
            </dl>
          )
        }
      >
        {/* biome-ignore lint/a11y/useSemanticElements: two buttons that pick which list shows. */}
        <div role="group" aria-label={text.invoices.tabs} className="flex w-fit gap-1 rounded-xl bg-white/12 p-0.75">
          {tabs.map((option) => (
            <button
              key={option.id ?? "waiting"}
              type="button"
              aria-pressed={option.id === tab}
              onClick={() => void navigate({ search: { tab: option.id }, replace: true })}
              className={cn(
                "h-10 cursor-pointer rounded-[9px] px-4 text-[15px] font-bold",
                option.id === tab ? "bg-card text-ledger" : "text-white hover:bg-white/8",
              )}
            >
              {option.label}
            </button>
          ))}
        </div>
      </AdminHero>

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col gap-3">
          {isSuccess && rows.length === 0 && (
            <p className="rounded-2xl border bg-card px-4 py-8 text-center text-muted-foreground">
              {paid ? text.invoices.nonePaid : text.invoices.none}
            </p>
          )}
          {rows.length > 0 && (
            <ul className="flex flex-col gap-3">
              {rows.map((invoice) => {
                const days = Math.floor((Date.now() - new Date(invoice.createdAt).getTime()) / DAY);
                return (
                  <li
                    key={invoice.id}
                    className="flex flex-wrap items-center gap-x-4.5 gap-y-3 rounded-2xl border bg-card p-4 md:px-5"
                  >
                    <div className="flex min-w-0 flex-[1_1_240px] flex-col gap-0.5">
                      <span className="text-sm text-muted-foreground">
                        {invoice.number} ·{" "}
                        {paid ? (
                          text.invoices.paidOn(format.date(invoice.createdAt))
                        ) : days >= 2 ? (
                          <strong className="text-[#7a4b00]">{text.invoices.waitingDays(format.number(days))}</strong>
                        ) : (
                          text.invoices.asked(format.date(invoice.createdAt))
                        )}
                      </span>
                      <Link
                        to="/admin/businesses/$businessId"
                        params={{ businessId: invoice.businessId }}
                        className="text-[17px] font-bold break-words text-foreground no-underline hover:underline"
                      >
                        {invoice.businessName}
                      </Link>
                      <span className="text-[15px] text-foreground/80">{what(invoice)}</span>
                    </div>
                    <span className="text-[26px] font-bold">{format.taka(invoice.amount)}</span>
                    {!paid && (
                      <button
                        type="button"
                        aria-label={`${text.invoices.markPaid}: ${invoice.number}`}
                        onClick={() => setMarking(invoice)}
                        className="min-h-11 cursor-pointer rounded-xl bg-primary px-4 font-bold text-primary-foreground hover:bg-primary/90"
                      >
                        {text.invoices.markPaid}
                      </button>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
          {!paid && rows.length > 0 && <p className="px-1 text-sm text-muted-foreground">{text.invoices.checkHint}</p>}
          <AddressPagination result={data} />
        </div>

        <AdminCard title={text.invoices.ledger}>
          <p className="-mt-1 text-sm text-muted-foreground">{text.invoices.ledgerHint}</p>
          <Field
            label={text.invoices.month}
            type="month"
            value={month}
            onChange={(event) => setMonth(event.target.value)}
          />
          <button
            type="button"
            disabled={download.isPending || !month}
            onClick={() => download.mutate()}
            className="flex h-11.5 cursor-pointer items-center justify-center gap-2 rounded-xl border-[1.5px] border-primary font-bold text-primary hover:bg-accent disabled:opacity-60"
          >
            <Download aria-hidden="true" className="size-4.5" />
            {text.invoices.download}
          </button>
        </AdminCard>
      </div>
      {marking && <MarkPaidDialog invoice={marking} what={what(marking)} onClose={() => setMarking(undefined)} />}
    </div>
  );
}

function MarkPaidDialog({ invoice, what, onClose }: { invoice: AdminInvoice; what: string; onClose: () => void }) {
  const { t } = useTranslation();
  const text = useAdminTexts();
  const format = useFormat();
  const queryClient = useQueryClient();
  const [reference, setReference] = useState("");
  const [missing, setMissing] = useState(false);
  const mark = useMutation({
    mutationFn: (bank: string) =>
      call(
        api.POST("/api/v1/admin/billing/invoices/{id}/mark-paid", {
          params: { path: { id: invoice.id } },
          body: { reference: bank },
        }),
      ),
    meta: { showsOwnErrors: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin"] });
      toast.success(text.invoices.done);
      onClose();
    },
  });
  return (
    <AdminDialog
      title={text.invoices.title(invoice.number)}
      description={text.invoices.description(invoice.businessName, format.taka(invoice.amount))}
      action={text.invoices.markPaid}
      problem={mark.error ? errorMessages(mark.error) : undefined}
      pending={mark.isPending}
      onClose={onClose}
      onSubmit={() => {
        const bank = reference.trim();
        setMissing(!bank);
        if (bank) {
          mark.mutate(bank);
        }
      }}
    >
      <dl className="grid grid-cols-2 gap-2">
        <div className="rounded-[10px] bg-muted/70 px-3 py-2.5">
          <dt className="text-[13px] text-muted-foreground">{text.invoices.amount}</dt>
          <dd className="text-lg font-bold">{format.taka(invoice.amount)}</dd>
        </div>
        <div className="rounded-[10px] bg-muted/70 px-3 py-2.5">
          <dt className="text-[13px] text-muted-foreground">{text.invoices.askedOn}</dt>
          <dd className="text-lg font-bold">{format.date(invoice.createdAt)}</dd>
        </div>
      </dl>
      <p className="-mt-1 text-sm text-muted-foreground">{what}</p>
      <Field
        label={text.invoices.reference}
        hint={text.invoices.referenceHint}
        autoComplete="off"
        maxLength={100}
        value={reference}
        error={missing ? t("field.required") : undefined}
        onChange={(event) => setReference(event.target.value)}
      />
    </AdminDialog>
  );
}
