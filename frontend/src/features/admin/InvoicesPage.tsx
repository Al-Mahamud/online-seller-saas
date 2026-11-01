import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { api, call } from "@/api/client";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { AddressPagination } from "@/components/layout/Pagination";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { AdminDialog } from "./AdminDialog";
import { type AdminInvoice, downloadLedger, openInvoicesQuery } from "./api";
import { useAdminTexts } from "./texts";

const route = getRouteApi("/admin/invoices");

/** Bank transfers still to be seen arriving, and the month's ledger for the accountant. */
export function InvoicesPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const { data, isSuccess } = useQuery(openInvoicesQuery(route.useSearch()));
  const waiting = data?.items ?? [];
  const [marking, setMarking] = useState<AdminInvoice>();
  const [month, setMonth] = useState(dhakaToday().slice(0, 7));
  const download = useMutation({ mutationFn: () => downloadLedger(month) });
  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{text.nav.invoices}</h1>
        <p className="text-muted-foreground">{text.invoices.hint}</p>
      </div>
      {isSuccess && waiting.length === 0 && <p className="py-4 text-muted-foreground">{text.invoices.none}</p>}
      {waiting.length > 0 && (
        <ul className="flex flex-col rounded-xl border bg-card px-4">
          {waiting.map((invoice) => (
            <li
              key={invoice.id}
              className="flex flex-wrap items-center justify-between gap-3 border-t py-3 first:border-t-0"
            >
              <span className="min-w-0">
                <span className="block font-semibold">
                  {invoice.number} · {format.taka(invoice.amount)}
                </span>
                <Link
                  to="/admin/businesses/$businessId"
                  params={{ businessId: invoice.businessId }}
                  className="block text-primary"
                >
                  {invoice.businessName}
                </Link>
                <span className="block text-sm text-muted-foreground">{format.date(invoice.createdAt)}</span>
              </span>
              <Button
                variant="outline"
                aria-label={`${text.invoices.markPaid}: ${invoice.number}`}
                onClick={() => setMarking(invoice)}
              >
                {text.invoices.markPaid}
              </Button>
            </li>
          ))}
        </ul>
      )}
      <AddressPagination result={data} />
      <Section title={text.invoices.ledger}>
        <div className="flex flex-wrap items-end gap-3">
          <Field
            label={text.invoices.month}
            type="month"
            value={month}
            onChange={(event) => setMonth(event.target.value)}
          />
          <Button variant="outline" disabled={download.isPending || !month} onClick={() => download.mutate()}>
            {text.invoices.download}
          </Button>
        </div>
      </Section>
      {marking && <MarkPaidDialog invoice={marking} onClose={() => setMarking(undefined)} />}
    </div>
  );
}

function MarkPaidDialog({ invoice, onClose }: { invoice: AdminInvoice; onClose: () => void }) {
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
      description={text.invoices.description(format.taka(invoice.amount), invoice.businessName)}
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
      <Field
        label={text.invoices.reference}
        autoComplete="off"
        maxLength={100}
        value={reference}
        error={missing ? t("field.required") : undefined}
        onChange={(event) => setReference(event.target.value)}
      />
    </AdminDialog>
  );
}
