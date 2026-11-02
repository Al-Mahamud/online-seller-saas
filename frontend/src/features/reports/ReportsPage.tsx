import { useMutation, useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Download as DownloadIcon } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { BackToMore } from "@/components/layout/PageHeader";
import { addDays, dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type Download, download, type GroupBy, profitQuery, salesQuery, salesToCompareQuery } from "./api";
import {
  COST_COLOR,
  changeFrom,
  daysBetween,
  PROFIT_COLOR,
  rankedSlices,
  type Slice,
  slices,
  WRITE_OFF_COLOR,
} from "./charts";
import { DayChart, Pie } from "./ReportCharts";

const route = getRouteApi("/_app/reports/");
const GROUPS: GroupBy[] = ["day", "product", "category"];
type ShareBy = Exclude<GroupBy, "day">;
const SHARES: ShareBy[] = ["product", "category"];
const CARD = "rounded-[14px] border bg-card";

const signed = (amount: number, taka: (amount: number) => string) => (amount < 0 ? `−${taka(-amount)}` : taka(amount));

function Choice({ on, onClick, children }: { on: boolean; onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      className={cn(
        "h-11 flex-none cursor-pointer rounded-[10px] border-[1.5px] px-3.5 text-[15px] font-semibold focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring",
        on
          ? "border-primary bg-primary text-primary-foreground"
          : "border-input bg-card text-foreground hover:bg-muted",
      )}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

/** Sales over any days the plan reaches: how they split, by day, product or category; profit for managers and owners. */
export function ReportsPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const business = useBusiness();
  const today = dhakaToday();
  const to = search.to ?? today;
  const from = search.from ?? addDays(to, -29);
  const by = search.by ?? "day";
  const canSeeProfit = hasRole(business, "MANAGER");
  const change = (next: Partial<typeof search>) => void navigate({ search: { ...search, ...next }, replace: true });
  const presets = [
    { label: t("reports.last7"), from: addDays(today, -6) },
    { label: t("reports.last30"), from: addDays(today, -29) },
    { label: t("reports.thisMonth"), from: `${today.slice(0, 8)}01` },
  ];
  const days = useQuery(salesQuery(from, to, "day"));
  const profit = useQuery({ ...profitQuery(from, to), enabled: canSeeProfit });
  const length = daysBetween(from, to);
  const before = useQuery(salesToCompareQuery(addDays(from, -length), addDays(from, -1)));
  const orders = (days.data?.rows ?? []).reduce((sum, row) => sum + (row.orders ?? 0), 0);
  const difference = days.data && before.data ? changeFrom(before.data.value, days.data.value) : null;
  const shownDays = format.number(length);
  const compared =
    difference === null
      ? t("reports.daysOnly", { days: shownDays })
      : difference === 0
        ? t("reports.compareSame", { days: shownDays })
        : t(difference > 0 ? "reports.compareMore" : "reports.compareLess", {
            days: shownDays,
            percent: format.number(Math.abs(difference)),
          });
  const net = profit.data?.profit.netProfit;
  const hasSales = (days.data?.value ?? 0) > 0;
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title and the period share one dark header. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-1 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="max-md:[&_a]:text-marigold-text">
          <BackToMore />
          <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("reports.title")}</h1>
        </div>
        <section
          aria-label={t("reports.period")}
          className="flex flex-col gap-3.5 md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:pt-4.5 md:pr-6 md:pb-5 md:pl-14 md:text-white"
        >
          <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
          <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2.5">
            <div className="grid min-w-0 flex-[1_1_100%] grid-cols-3 gap-1.5 md:flex-[1_1_330px]">
              {presets.map((preset) => {
                const active = from === preset.from && to === today;
                return (
                  <button
                    key={preset.label}
                    type="button"
                    aria-pressed={active}
                    className={cn(
                      "h-11.5 cursor-pointer rounded-[10px] border-[1.5px] px-1 text-[15px] font-bold whitespace-nowrap focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text",
                      active
                        ? "border-marigold-text bg-marigold-text text-ledger"
                        : "border-ledger-text text-white hover:bg-white/10",
                    )}
                    onClick={() => change({ from: preset.from, to: undefined })}
                  >
                    {preset.label}
                  </button>
                );
              })}
            </div>
            <div className="grid min-w-0 flex-[1_1_100%] grid-cols-2 gap-2.5 md:flex-[1_1_340px] [&_input]:h-11.5 [&_input]:rounded-[10px] [&_input]:border-0 [&_input]:bg-card [&_input]:px-2 [&_input]:text-[15px] [&_input]:text-foreground [&_label]:text-sm [&_label]:text-ledger-text">
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
          {days.data && (
            <dl
              className={cn(
                "grid grid-cols-2 gap-x-4 gap-y-2.5 border-t border-ledger-outline pt-3.5 md:gap-5",
                canSeeProfit ? "md:grid-cols-[1.6fr_1fr_0.7fr_0.7fr]" : "md:grid-cols-[1.6fr_1fr_1fr]",
              )}
            >
              <div className="max-md:col-span-2">
                <dt className="text-[15px] font-semibold text-ledger-text">{t("reports.revenue")}</dt>
                <dd className="text-4xl leading-tight font-bold text-marigold-text md:text-[38px]">
                  {format.taka(days.data.value)}
                </dd>
                <dd className="text-[15px] text-ledger-text">{compared}</dd>
              </div>
              {canSeeProfit && net !== undefined && (
                <div>
                  <dt className="text-[15px] font-semibold text-ledger-text">
                    {t(net < 0 ? "reports.netLoss" : "reports.netProfit")}
                  </dt>
                  <dd className="text-2xl font-bold">{signed(net, format.taka)}</dd>
                </div>
              )}
              <div>
                <dt className="text-[15px] font-semibold text-ledger-text">{t("reports.column.orders")}</dt>
                <dd className="text-2xl font-bold">{t("reports.count", { count: format.number(orders) })}</dd>
              </div>
              <div>
                <dt className="text-[15px] font-semibold text-ledger-text">{t("reports.itemsSold")}</dt>
                <dd className="text-2xl font-bold">
                  {t("reports.count", { count: format.number(days.data.quantity) })}
                </dd>
              </div>
            </dl>
          )}
        </section>
      </div>
      <div
        className={cn(
          "grid gap-4 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5",
          canSeeProfit && "lg:grid-cols-[minmax(0,1fr)_340px] lg:items-start",
        )}
      >
        <div className="flex min-w-0 flex-col gap-4">
          {hasSales && (
            <SalesShare
              from={from}
              to={to}
              total={days.data?.value ?? 0}
              by={search.share ?? "product"}
              onBy={(next) => change({ share: next === "product" ? undefined : next })}
            />
          )}
          <Sales from={from} to={to} by={by} onBy={(next) => change({ by: next === "day" ? undefined : next })} />
        </div>
        {canSeeProfit && (
          <aside className="flex min-w-0 flex-col gap-4">
            <Profit from={from} to={to} />
            <Downloads from={from} to={to} />
          </aside>
        )}
      </div>
    </div>
  );
}

/** How the sales split: a pie, with its names and figures beside it. */
function SalesShare({
  from,
  to,
  total,
  by,
  onBy,
}: {
  from: string;
  to: string;
  total: number;
  by: ShareBy;
  onBy: (by: ShareBy) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const [chosen, setChosen] = useState<string>();
  const { data } = useQuery(salesQuery(from, to, by));
  const shown = rankedSlices(
    (data?.rows ?? []).map((row) => ({ key: row.key, name: row.label, value: row.value })),
    t("reports.other"),
  );
  const percent = (slice: Slice) => t("reports.percent", { percent: format.number(Math.round(slice.share * 100)) });
  const picked = shown.find((slice) => slice.key === chosen);
  const first = shown[0];
  const sum = shown.reduce((all, slice) => all + slice.value, 0);
  return (
    <section aria-labelledby="sales-share" className={cn(CARD, "px-3.5 pt-3 pb-2 md:px-5 md:pt-3.5")}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <div className="min-w-0">
          <h2 id="sales-share" className="text-[21px] font-bold">
            {t("reports.shareTitle")}
          </h2>
          <p aria-live="polite" className="min-h-5.5 text-sm text-muted-foreground">
            {picked
              ? t("reports.shareChosen", {
                  name: picked.name,
                  amount: format.taka(picked.value),
                  percent: percent(picked),
                })
              : first && shown.length > 1 && t("reports.shareBiggest", { name: first.name, percent: percent(first) })}
          </p>
        </div>
        {/* biome-ignore lint/a11y/useSemanticElements: two buttons that belong together, with no form around them. */}
        <div role="group" aria-label={t("reports.shareOf")} className="flex gap-1.5">
          {SHARES.map((share) => (
            <Choice
              key={share}
              on={share === by}
              onClick={() => {
                setChosen(undefined);
                onBy(share);
              }}
            >
              {t(`reports.column.${share}`)}
            </Choice>
          ))}
        </div>
      </div>
      {first && shown.length === 1 && (
        <p className="pt-2 pb-2.5">{t("reports.shareOne", { name: first.name, amount: format.taka(first.value) })}</p>
      )}
      {shown.length > 1 && (
        // biome-ignore lint/a11y/noStaticElementInteractions: letting go of the pie is only for a pointer.
        <div
          className="flex flex-wrap items-center justify-center gap-x-7 gap-y-3 pt-3 pb-1.5"
          onMouseLeave={() => setChosen(undefined)}
        >
          <Pie
            shown={shown}
            size={220}
            className="max-md:size-50"
            chosen={chosen}
            onChoose={setChosen}
            label={t("reports.shareLabel", {
              list: shown.map((slice) => `${slice.name} ${percent(slice)}`).join(", "),
            })}
          />
          <ul className="min-w-0 flex-[1_1_280px]">
            {shown.map((slice) => (
              <li key={slice.key}>
                <button
                  type="button"
                  className={cn(
                    "flex min-h-11 w-full cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1 text-left",
                    chosen === slice.key && "bg-muted",
                  )}
                  onMouseEnter={() => setChosen(slice.key)}
                  onFocus={() => setChosen(slice.key)}
                  onClick={() => setChosen(slice.key)}
                >
                  <span
                    aria-hidden="true"
                    className="size-3.5 flex-none rounded-[4px]"
                    style={{ background: slice.color }}
                  />
                  <span className="min-w-0 flex-1 text-[15px] font-semibold break-words">{slice.name}</span>
                  <span className="flex-none text-[15px] font-bold">{percent(slice)}</span>
                  <span className="w-20 flex-none text-right text-sm whitespace-nowrap text-muted-foreground">
                    {format.taka(slice.value)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {sum !== total && shown.length > 1 && (
        <p className="pb-2 text-sm text-muted-foreground">{t("reports.shareNote")}</p>
      )}
    </section>
  );
}

function Sales({ from, to, by, onBy }: { from: string; to: string; by: GroupBy; onBy: (by: GroupBy) => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const days = useQuery(salesQuery(from, to, "day"));
  const { data, error } = useQuery(salesQuery(from, to, by));
  const byDay = by === "day";
  // Days without sales are in the answer as zeros; newest first is what a seller looks for.
  const rows = byDay ? [...(data?.rows ?? [])].filter((row) => row.orders !== 0).reverse() : (data?.rows ?? []);
  const orders = rows.reduce((sum, row) => sum + (row.orders ?? 0), 0);
  const delivery = rows.reduce((sum, row) => sum + (row.deliveryCharged ?? 0), 0);
  const dayLine = (dayOrders: number, quantity: number, charged: number) =>
    t("reports.rowLine", {
      orders: format.number(dayOrders),
      quantity: format.number(quantity),
      delivery: format.taka(charged),
    });
  const heading = (
    <h2 id="sales-title" className="text-[21px] font-bold">
      {t("reports.sales")}
    </h2>
  );
  return (
    <section aria-labelledby="sales-title" className={cn(CARD, "overflow-hidden")}>
      <div className="px-3.5 pt-3 pb-3 md:px-5 md:pt-3.5">
        {(days.data?.value ?? 0) > 0 ? (
          <DayChart key={`${from}:${to}`} heading={heading} rows={days.data?.rows ?? []} />
        ) : (
          heading
        )}
      </div>
      {error && (
        <p
          role="alert"
          className="mx-3.5 mb-4 rounded-[10px] bg-danger-soft px-3.5 py-2.75 text-[15px] font-semibold text-danger-ink md:mx-5"
        >
          {errorMessages(error)}
        </p>
      )}
      {data && rows.length === 0 && (
        <p className="border-t px-4 pt-7 pb-9 text-center text-[17px] font-bold">{t("reports.none")}</p>
      )}
      {data && rows.length > 0 && (
        <>
          {/* biome-ignore lint/a11y/useSemanticElements: three buttons that belong together, with no form around them. */}
          <div
            role="group"
            aria-label={t("reports.viewBy")}
            className="flex gap-1.5 overflow-x-auto px-3.5 pb-2.5 md:px-5"
          >
            {GROUPS.map((group) => (
              <Choice key={group} on={group === by} onClick={() => onBy(group)}>
                {t(`reports.by.${group}`)}
              </Choice>
            ))}
          </div>
          <table className="w-full border-collapse text-[15px]">
            <thead>
              <tr className="bg-muted text-left text-sm text-muted-foreground">
                <th scope="col" className="py-2 pr-2.5 pl-3.5 font-semibold md:pl-5">
                  {t(`reports.column.${by}`)}
                </th>
                {byDay && (
                  <th scope="col" className="hidden px-2.5 py-2 text-right font-semibold md:table-cell">
                    {t("reports.column.orders")}
                  </th>
                )}
                <th scope="col" className="hidden px-2.5 py-2 text-right font-semibold md:table-cell">
                  {t("reports.column.quantity")}
                </th>
                <th
                  scope="col"
                  className={cn("py-2 pl-2.5 text-right font-semibold", byDay ? "pr-3.5 md:pr-2.5" : "pr-3.5 md:pr-5")}
                >
                  {t("reports.column.value")}
                </th>
                {byDay && (
                  <th scope="col" className="hidden py-2 pr-5 pl-2.5 text-right font-semibold md:table-cell">
                    {t("reports.column.delivery")}
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.key} className="border-t">
                  <th scope="row" className="py-2.25 pr-2.5 pl-3.5 text-left font-semibold break-words md:pl-5">
                    {byDay ? format.date(row.key) : row.label}
                    <span className="block text-sm font-normal text-muted-foreground md:hidden">
                      {byDay
                        ? dayLine(row.orders ?? 0, row.quantity, row.deliveryCharged ?? 0)
                        : t("reports.count", { count: format.number(row.quantity) })}
                    </span>
                  </th>
                  {byDay && (
                    <td className="hidden px-2.5 py-2.25 text-right md:table-cell">{format.number(row.orders ?? 0)}</td>
                  )}
                  <td className="hidden px-2.5 py-2.25 text-right md:table-cell">{format.number(row.quantity)}</td>
                  <td
                    className={cn(
                      "py-2.25 pl-2.5 text-right font-bold whitespace-nowrap",
                      byDay ? "pr-3.5 md:pr-2.5" : "pr-3.5 md:pr-5",
                    )}
                  >
                    {format.taka(row.value)}
                  </td>
                  {byDay && (
                    <td className="hidden py-2.25 pr-5 pl-2.5 text-right whitespace-nowrap md:table-cell">
                      {format.taka(row.deliveryCharged ?? 0)}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t-[1.5px] border-input font-bold">
                <th scope="row" className="py-2.5 pr-2.5 pl-3.5 text-left md:pl-5">
                  {t("reports.total")}
                  <span className="block text-sm font-normal text-muted-foreground md:hidden">
                    {byDay
                      ? dayLine(orders, data.quantity, delivery)
                      : t("reports.count", { count: format.number(data.quantity) })}
                  </span>
                </th>
                {byDay && <td className="hidden px-2.5 py-2.5 text-right md:table-cell">{format.number(orders)}</td>}
                <td className="hidden px-2.5 py-2.5 text-right md:table-cell">{format.number(data.quantity)}</td>
                <td
                  className={cn(
                    "py-2.5 pl-2.5 text-right whitespace-nowrap",
                    byDay ? "pr-3.5 md:pr-2.5" : "pr-3.5 md:pr-5",
                  )}
                >
                  {format.taka(data.value)}
                </td>
                {byDay && (
                  <td className="hidden py-2.5 pr-5 pl-2.5 text-right whitespace-nowrap md:table-cell">
                    {format.taka(delivery)}
                  </td>
                )}
              </tr>
            </tfoot>
          </table>
          <p className="px-3.5 pt-2.5 pb-3.5 text-sm text-muted-foreground md:px-5">
            {t(byDay ? "reports.dayNote" : "reports.itemNote")}
          </p>
        </>
      )}
    </section>
  );
}

function ProfitLine({
  label,
  value,
  color,
  strong,
}: {
  label: string;
  value: string;
  color?: string;
  strong?: boolean;
}) {
  return (
    <div className={cn("flex justify-between gap-3", strong && "border-t py-1.5 font-bold")}>
      <dt className="flex items-center gap-2">
        {color && <span aria-hidden="true" className="size-3 flex-none rounded-[3px]" style={{ background: color }} />}
        {label}
      </dt>
      <dd className="whitespace-nowrap">{value}</dd>
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
  const whole = profit.costOfGoods + profit.writeOffs + profit.grossProfit;
  const percent = (part: number) =>
    t("reports.percent", { percent: format.number(whole > 0 ? Math.round((part / whole) * 100) : 0) });
  // A pie has no way to show less than nothing, so it only appears when the sales covered their costs.
  const split =
    profit.grossProfit > 0
      ? slices([
          { key: "cost", name: t("reports.costOfGoods"), value: profit.costOfGoods, color: COST_COLOR },
          { key: "off", name: t("reports.writeOffs"), value: profit.writeOffs, color: WRITE_OFF_COLOR },
          { key: "profit", name: t("reports.grossProfit"), value: profit.grossProfit, color: PROFIT_COLOR },
        ])
      : [];
  const loss = profit.netProfit < 0;
  return (
    <section aria-labelledby="profit-title" className={cn(CARD, "px-4.5 pt-3.5 pb-4")}>
      <h2 id="profit-title" className="text-[21px] font-bold">
        {t("reports.profit")}
      </h2>
      {split.length > 0 && (
        <div className="flex justify-center pt-1 pb-2.5">
          <Pie
            shown={split}
            size={150}
            label={t("reports.profitLabel", {
              cost: percent(profit.costOfGoods),
              off: percent(profit.writeOffs),
              gross: percent(profit.grossProfit),
            })}
          />
        </div>
      )}
      <dl className="flex flex-col gap-1.5 text-[15px] max-lg:mt-2 lg:mt-1">
        <ProfitLine label={t("reports.revenue")} value={format.taka(profit.revenue)} />
        <ProfitLine label={t("reports.costOfGoods")} value={`−${format.taka(profit.costOfGoods)}`} color={COST_COLOR} />
        <ProfitLine
          label={t("reports.writeOffs")}
          value={`−${format.taka(profit.writeOffs)}`}
          color={WRITE_OFF_COLOR}
        />
        <ProfitLine
          strong
          label={t("reports.grossProfit")}
          value={signed(profit.grossProfit, format.taka)}
          color={PROFIT_COLOR}
        />
        <ProfitLine label={t("reports.deliveryCharged")} value={format.taka(profit.deliveryCharged)} />
        <ProfitLine label={t("reports.courierCharges")} value={`−${format.taka(profit.courierCharges)}`} />
        <ProfitLine strong label={t("reports.deliveryMargin")} value={signed(profit.deliveryMargin, format.taka)} />
        <div
          className={cn(
            "flex items-baseline justify-between gap-3 rounded-[10px] px-3 py-2.5 font-bold",
            loss ? "bg-danger-soft text-danger-ink" : "bg-accent text-accent-foreground",
          )}
        >
          <dt>{t(loss ? "reports.netLoss" : "reports.netProfit")}</dt>
          <dd className="text-[21px]">{signed(profit.netProfit, format.taka)}</dd>
        </div>
      </dl>
      {profit.itemsWithoutCost > 0 && (
        <p className="mt-2.5 rounded-[10px] bg-warning px-3 py-2.5 text-sm font-semibold text-warning-foreground">
          {t("reports.withoutCost", { count: format.number(profit.itemsWithoutCost) })}{" "}
          <Link to="/products" className="text-warning-foreground underline">
            {t("nav.products")}
          </Link>
        </p>
      )}
    </section>
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
    <section aria-labelledby="downloads-title" className={cn(CARD, "overflow-hidden")}>
      <h2 id="downloads-title" className="px-4.5 py-3.25 text-[21px] font-bold">
        {t("reports.downloads")}
      </h2>
      <ul>
        {items.map((item) => (
          <li key={item.what} className="border-t">
            <button
              type="button"
              disabled={save.isPending}
              className="flex min-h-13.5 w-full cursor-pointer items-center justify-between gap-3 px-4.5 py-2 text-left hover:bg-background disabled:cursor-default disabled:opacity-60"
              onClick={() => save.mutate(item.what)}
            >
              <span className="min-w-0">
                <span className="block text-[15px] font-semibold">{item.label}</span>
                {item.hint && <span className="block text-sm text-muted-foreground">{item.hint}</span>}
              </span>
              <DownloadIcon aria-hidden="true" className="size-5 flex-none text-primary" />
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
