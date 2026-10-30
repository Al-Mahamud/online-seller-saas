import { useMutation, useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Download as DownloadIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { Section } from "@/components/layout/Section";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { addDays, dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type Download, download, type GroupBy, profitQuery, salesQuery } from "./api";

const route = getRouteApi("/_app/reports/");
const GROUPS: GroupBy[] = ["day", "product", "category"];

/** Sales over any days the plan reaches: by day, product or category; profit for managers and owners. */
export function ReportsPage() {
  const { t } = useTranslation();
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const business = useBusiness();
  const today = dhakaToday();
  const to = search.to ?? today;
  const from = search.from ?? addDays(to, -29);
  const by = search.by ?? "day";
  const change = (next: Partial<typeof search>) => void navigate({ search: { ...search, ...next }, replace: true });
  const presets = [
    { label: t("reports.last7"), from: addDays(today, -6) },
    { label: t("reports.last30"), from: addDays(today, -29) },
    { label: t("reports.thisMonth"), from: `${today.slice(0, 8)}01` },
  ];
  return (
    <div className="flex max-w-3xl flex-col gap-4">
      <div>
        <BackToMore />
        <PageHeader title={t("reports.title")} />
      </div>
      <div className="flex flex-col gap-3 rounded-xl border bg-card p-4">
        <div className="flex flex-wrap gap-2">
          {presets.map((preset) => {
            const active = from === preset.from && to === today;
            return (
              <Button
                key={preset.label}
                size="sm"
                variant={active ? "default" : "outline"}
                className="min-h-11"
                aria-pressed={active}
                onClick={() => change({ from: preset.from, to: undefined })}
              >
                {preset.label}
              </Button>
            );
          })}
        </div>
        <div className="grid grid-cols-2 gap-3 sm:max-w-sm">
          <Field
            label={t("orders.from")}
            type="date"
            max={to}
            value={from}
            onChange={(event) => change({ from: event.target.value || undefined })}
          />
          <Field
            label={t("orders.to")}
            type="date"
            max={today}
            value={to}
            onChange={(event) => change({ to: event.target.value || undefined })}
          />
        </div>
      </div>
      <Sales from={from} to={to} by={by} onBy={(next) => change({ by: next === "day" ? undefined : next })} />
      {hasRole(business, "MANAGER") && <Profit from={from} to={to} />}
      {hasRole(business, "MANAGER") && <Downloads from={from} to={to} />}
    </div>
  );
}

function Sales({ from, to, by, onBy }: { from: string; to: string; by: GroupBy; onBy: (by: GroupBy) => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { data, error } = useQuery(salesQuery(from, to, by));
  const byDay = by === "day";
  // Days without sales are in the answer as zeros; newest first is what a seller looks for.
  const rows = byDay ? [...(data?.rows ?? [])].filter((row) => row.orders !== 0).reverse() : (data?.rows ?? []);
  const orders = rows.reduce((sum, row) => sum + (row.orders ?? 0), 0);
  const delivery = rows.reduce((sum, row) => sum + (row.deliveryCharged ?? 0), 0);
  return (
    <Section title={t("reports.sales")}>
      <div className="flex flex-wrap gap-1.5">
        {GROUPS.map((group) => (
          <Button
            key={group}
            size="sm"
            variant={group === by ? "default" : "outline"}
            className="min-h-11"
            aria-pressed={group === by}
            onClick={() => onBy(group)}
          >
            {t(`reports.by.${group}`)}
          </Button>
        ))}
      </div>
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{errorMessages(error)}</AlertDescription>
        </Alert>
      )}
      {data && rows.length === 0 && <p className="text-muted-foreground">{t("reports.none")}</p>}
      {data && rows.length > 0 && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full border-collapse text-[15px]">
              <thead>
                <tr className="text-left text-[13px] text-muted-foreground">
                  <th scope="col" className="border-b py-2 pr-3 font-semibold">
                    {t(`reports.column.${by}`)}
                  </th>
                  {byDay && (
                    <th scope="col" className="border-b px-2 py-2 text-right font-semibold">
                      {t("reports.column.orders")}
                    </th>
                  )}
                  <th scope="col" className="border-b px-2 py-2 text-right font-semibold">
                    {t("reports.column.quantity")}
                  </th>
                  <th scope="col" className="border-b px-2 py-2 text-right font-semibold">
                    {t("reports.column.value")}
                  </th>
                  {byDay && (
                    <th scope="col" className="border-b py-2 pl-2 text-right font-semibold">
                      {t("reports.column.delivery")}
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.key}>
                    <th scope="row" className="border-b py-2 pr-3 text-left font-medium">
                      {byDay ? format.date(row.key) : row.label}
                    </th>
                    {byDay && <td className="border-b px-2 py-2 text-right">{format.number(row.orders ?? 0)}</td>}
                    <td className="border-b px-2 py-2 text-right">{format.number(row.quantity)}</td>
                    <td className="border-b px-2 py-2 text-right font-semibold whitespace-nowrap">
                      {format.taka(row.value)}
                    </td>
                    {byDay && (
                      <td className="border-b py-2 pl-2 text-right whitespace-nowrap">
                        {format.taka(row.deliveryCharged ?? 0)}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="font-bold">
                  <th scope="row" className="py-2 pr-3 text-left">
                    {t("reports.total")}
                  </th>
                  {byDay && <td className="px-2 py-2 text-right">{format.number(orders)}</td>}
                  <td className="px-2 py-2 text-right">{format.number(data.quantity)}</td>
                  <td className="px-2 py-2 text-right whitespace-nowrap">{format.taka(data.value)}</td>
                  {byDay && <td className="py-2 pl-2 text-right whitespace-nowrap">{format.taka(delivery)}</td>}
                </tr>
              </tfoot>
            </table>
          </div>
          <p className="text-sm text-muted-foreground">{t(byDay ? "reports.dayNote" : "reports.itemNote")}</p>
        </>
      )}
    </Section>
  );
}

function ProfitLine({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={cn("flex justify-between gap-3", strong && "border-t pt-2 font-bold")}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Profit({ from, to }: { from: string; to: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { data } = useQuery(profitQuery(from, to));
  if (!data) {
    return null;
  }
  const { profit } = data;
  return (
    <Section title={t("reports.profit")}>
      <dl className="flex flex-col gap-1">
        <ProfitLine label={t("reports.revenue")} value={format.taka(profit.revenue)} />
        <ProfitLine label={t("reports.costOfGoods")} value={`−${format.taka(profit.costOfGoods)}`} />
        {profit.writeOffs > 0 && (
          <ProfitLine label={t("reports.writeOffs")} value={`−${format.taka(profit.writeOffs)}`} />
        )}
        <ProfitLine label={t("reports.grossProfit")} value={format.taka(profit.grossProfit)} strong />
        <ProfitLine label={t("reports.deliveryCharged")} value={format.taka(profit.deliveryCharged)} />
        <ProfitLine label={t("reports.courierCharges")} value={`−${format.taka(profit.courierCharges)}`} />
        <ProfitLine label={t("reports.deliveryMargin")} value={format.taka(profit.deliveryMargin)} strong />
        <ProfitLine label={t("reports.netProfit")} value={format.taka(profit.netProfit)} strong />
      </dl>
      {profit.itemsWithoutCost > 0 && (
        <p className="rounded-lg bg-warning p-3 text-warning-foreground">
          {t("reports.withoutCost", { count: format.number(profit.itemsWithoutCost) })}{" "}
          <Link to="/products" className="text-warning-foreground underline">
            {t("nav.products")}
          </Link>
        </p>
      )}
    </Section>
  );
}

function Downloads({ from, to }: { from: string; to: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const save = useMutation({ mutationFn: (what: Download) => download(what, from, to) });
  const items: { what: Download; label: string; hint?: string }[] = [
    {
      what: "orders",
      label: t("reports.download.orders"),
      hint: t("reports.download.ordersHint", { from: format.date(from), to: format.date(to) }),
    },
    { what: "products", label: t("reports.download.products") },
    { what: "customers", label: t("reports.download.customers") },
  ];
  if (hasRole(business, "OWNER")) {
    items.push({
      what: "everything",
      label: t("reports.download.everything"),
      hint: t("reports.download.everythingHint"),
    });
  }
  return (
    <Section title={t("reports.downloads")}>
      <ul className="flex flex-col gap-3">
        {items.map((item) => (
          <li key={item.what} className="flex flex-col items-start gap-1">
            <Button variant="outline" disabled={save.isPending} onClick={() => save.mutate(item.what)}>
              <DownloadIcon aria-hidden="true" />
              {item.label}
            </Button>
            {item.hint && <p className="text-sm text-muted-foreground">{item.hint}</p>}
          </li>
        ))}
      </ul>
    </Section>
  );
}
