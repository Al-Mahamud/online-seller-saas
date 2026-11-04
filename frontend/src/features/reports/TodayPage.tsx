import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link, type LinkProps } from "@tanstack/react-router";
import { Check, ChevronRight, Clock, CreditCard, Package, Plus } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { can, useBusiness } from "@/api/business";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useCanWrite } from "@/features/billing/status";
import { lowStockQuery } from "@/features/catalog/api";
import { LowStockLink } from "@/features/catalog/LowStockWindow";
import type { OrderStatus } from "@/features/orders/search";
import { dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { dashboardQuery, type Overview, overviewQuery, type Today } from "./api";
import { SalesChart } from "./SalesChart";

const route = getRouteApi("/_app/");
const SHOWN = 3;
const CARD = "flex min-w-0 flex-col gap-3.5 rounded-[14px] border bg-card p-4 md:px-5 md:py-4.5";
const OPEN_STATUSES = ["DRAFT", "CONFIRMED", "PACKED", "SHIPPED"] as const;
const RETURN_KINDS = ["FAILED_DELIVERY", "CUSTOMER_RETURN", "PARTIAL_DELIVERY"] as const;

/** What needs doing and how the business stands (docs/dashboard-redesign.md). */
export function TodayPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const canWrite = useCanWrite();
  const { date } = route.useSearch();
  const navigate = route.useNavigate();
  const today = dhakaToday();
  const day = date ?? today;
  const isToday = day === today;
  const { data, error } = useQuery(dashboardQuery(date));
  const { data: overview } = useQuery(overviewQuery);
  const choose = (next: string) => void navigate({ search: next && next !== today ? { date: next } : {} });
  const isNew = overview ? !overview.hasOrders : false;
  return (
    <div className="flex w-full flex-col gap-4.5">
      {/* On phones the title and the journey share one dark header; from 768 px each stands alone. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-3.5 max-md:bg-ledger max-md:pt-3 max-md:pr-4.5 max-md:pb-9 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="flex flex-wrap items-end justify-between gap-3.5">
          <div>
            <h1 className="text-[30px] leading-tight font-bold md:text-[32px]">
              {isToday ? t("nav.today") : format.fullDate(day)}
            </h1>
            <p className="max-md:text-ledger-text md:text-muted-foreground">
              {isToday ? (
                format.fullDate(day)
              ) : (
                <Link to="/" className="max-md:text-marigold-text">
                  {t("today.backToToday")}
                </Link>
              )}
            </p>
          </div>
          <div className="flex flex-wrap items-end gap-2.5">
            {!isNew && (
              <div className="w-44 [&_input]:bg-card [&_input]:text-foreground">
                <Field
                  label={t("today.pickDay")}
                  type="date"
                  max={today}
                  value={day}
                  onChange={(event) => choose(event.target.value)}
                />
              </div>
            )}
            {canWrite && (
              <Button asChild className="h-11 rounded-[10px] text-base font-semibold max-md:hidden">
                <Link to="/orders/new" className="text-primary-foreground no-underline">
                  <Plus aria-hidden="true" strokeWidth={2.5} />
                  {t("orders.new")}
                </Link>
              </Button>
            )}
          </div>
        </div>
        {data && <Journey data={data} isToday={isToday} isNew={isNew} />}
      </div>

      <div className="flex flex-col gap-4.5 max-md:relative max-md:-mx-4 max-md:-mt-8 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-4">
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{errorMessages(error)}</AlertDescription>
          </Alert>
        )}
        {isNew && overview && <GetStarted overview={overview} />}
        {!isNew && (
          <div className="flex flex-wrap items-stretch gap-4.5">
            {data && overview && <Attention data={data} overview={overview} />}
            <Business data={data} isToday={isToday}>
              <SalesChart today={today} chosen={day} onChoose={choose} />
            </Business>
          </div>
        )}
        {isNew && data && (
          <div className="flex flex-wrap items-stretch gap-4.5">
            <Business data={data} isToday={isToday} isNew />
          </div>
        )}
        {!isNew && (
          <div className="flex flex-wrap items-stretch gap-4.5">
            {overview && <Deliveries overview={overview} />}
            {data && <Products data={data} isToday={isToday} />}
          </div>
        )}
        {!isNew && (
          <Link to="/reports" className="flex min-h-11 w-fit items-center font-semibold no-underline">
            {t("today.allReports")}
          </Link>
        )}
      </div>
    </div>
  );
}

const STAGE =
  "grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 border-t border-ledger-line py-1.5 text-white no-underline hover:bg-white/5 hover:text-white focus-visible:outline-3 focus-visible:outline-marigold-text md:flex md:min-h-28 md:flex-col md:items-start md:gap-px md:rounded-b-[10px] md:border-t-2 md:px-2.5 md:pt-2 md:pb-2.5";

function Stage({
  status,
  label,
  count,
  note,
  action,
}: {
  status: OrderStatus;
  label: string;
  count: number;
  note?: string;
  action?: string;
}) {
  const format = useFormat();
  const finished = !action && !note;
  return (
    <Link
      to="/orders"
      search={{ status }}
      className={cn(
        STAGE,
        finished
          ? "max-md:flex max-md:min-h-18 max-md:flex-col max-md:items-start max-md:gap-0 border-marigold max-md:border-t-2"
          : "min-h-14.5 md:border-ledger-outline",
      )}
    >
      <span className="text-base font-semibold md:text-[15px]">{label}</span>
      <span
        className={cn(
          "col-start-2 row-span-2 row-start-1 text-[30px] leading-tight font-bold md:text-[38px]",
          count === 0 && "text-ledger-text",
        )}
      >
        {format.number(count)}
      </span>
      {action && count > 0 && <span className="col-start-1 text-sm font-semibold text-marigold-text">{action}</span>}
      {note && <span className="col-start-1 text-sm text-ledger-text">{note}</span>}
    </Link>
  );
}

/** Each order in one place: what's moving now, each with its next step, then what finished that day. */
function Journey({ data, isToday, isNew }: { data: Today; isToday: boolean; isNew: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const count = (status: OrderStatus) => data.byStatus[status] ?? 0;
  if (isNew) {
    return (
      <section
        aria-labelledby="journey"
        className="flex flex-col gap-3.5 md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-5 md:pr-7 md:pl-15 md:text-white"
      >
        <div aria-hidden="true" className="absolute inset-y-0 left-8 hidden w-0.5 bg-marigold md:block" />
        <h2 id="journey" className="text-xl font-bold max-md:sr-only">
          {t("today.journey")}
        </h2>
        <div className="flex flex-col gap-1.5">
          <p className="text-sm font-semibold text-ledger-text">{t("today.now")}</p>
          <div className="grid grid-cols-4 gap-2">
            {OPEN_STATUSES.map((status) => (
              <div key={status} className="border-t-2 border-ledger-outline pt-1.5">
                <p className="text-sm font-semibold">{t(`orderStatus.${status}`)}</p>
                <p className="text-[26px] leading-tight font-bold text-ledger-text">{format.number(0)}</p>
              </div>
            ))}
          </div>
        </div>
      </section>
    );
  }
  return (
    <section
      aria-labelledby="journey"
      className="flex flex-col gap-3.5 md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-5 md:pr-7 md:pl-15 md:text-white"
    >
      <div aria-hidden="true" className="absolute inset-y-0 left-8 hidden w-0.5 bg-marigold md:block" />
      <h2 id="journey" className="text-xl font-bold max-md:sr-only">
        {t("today.journey")}
      </h2>
      <div className="flex flex-wrap gap-x-7 gap-y-3.5">
        <div className="flex min-w-0 flex-[4_1_420px] flex-col gap-1.5 md:gap-2">
          <p className="text-sm font-semibold text-ledger-text">{t("today.now")}</p>
          <div className="grid border-b border-ledger-line md:grid-cols-4 md:gap-2 md:border-b-0">
            <Stage
              status="DRAFT"
              label={t("orderStatus.DRAFT")}
              count={count("DRAFT")}
              action={t("today.next.DRAFT")}
            />
            <Stage
              status="CONFIRMED"
              label={t("orderStatus.CONFIRMED")}
              count={count("CONFIRMED")}
              action={t("today.next.CONFIRMED")}
            />
            <Stage
              status="PACKED"
              label={t("orderStatus.PACKED")}
              count={count("PACKED")}
              action={t("today.next.PACKED")}
            />
            <Stage
              status="SHIPPED"
              label={t("orderStatus.SHIPPED")}
              count={count("SHIPPED")}
              note={t(isToday ? "today.sentToday" : "today.sentThatDay", {
                count: format.number(data.shipped),
              })}
            />
          </div>
        </div>
        <div className="flex min-w-0 flex-[3_1_280px] flex-col gap-1.5 md:gap-2">
          <p className="text-sm font-semibold text-ledger-text">
            {isToday ? t("today.finishedToday") : t("today.finishedThatDay")}
          </p>
          <div className="grid grid-cols-3 gap-2">
            <Stage status="DELIVERED" label={t("today.delivered")} count={count("DELIVERED")} />
            <Stage status="RETURNED" label={t("orderStatus.RETURNED")} count={count("RETURNED")} />
            <Stage status="CANCELLED" label={t("orderStatus.CANCELLED")} count={count("CANCELLED")} />
          </div>
        </div>
      </div>
    </section>
  );
}

function Problem({
  link,
  tone,
  icon,
  title,
  note,
}: {
  link: LinkProps;
  tone: "danger" | "warning";
  icon: ReactNode;
  title: string;
  note: string;
}) {
  return (
    <li>
      <Link
        {...link}
        className="flex min-h-18 items-center gap-3 rounded-[10px] p-2 text-foreground no-underline hover:bg-background hover:text-foreground"
      >
        <span
          aria-hidden="true"
          className={cn(
            "flex size-10 flex-none items-center justify-center rounded-[10px] [&_svg]:size-5",
            tone === "danger" ? "bg-danger-soft text-danger-ink" : "bg-warning text-warning-foreground",
          )}
        >
          {icon}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block font-semibold">{title}</span>
          <span className="block text-sm text-muted-foreground">{note}</span>
        </span>
        <ChevronRight aria-hidden="true" className="size-4.5 flex-none text-muted-foreground" />
      </Link>
    </li>
  );
}

/** Only what's wrong: when nothing is, it says so in a line. */
function Attention({ data, overview }: { data: Today; overview: Overview }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { lateParcels, stuckCod } = overview;
  const clear = lateParcels === 0 && stuckCod.length === 0 && data.lowStock === 0;
  return (
    <section aria-labelledby="attention" className={cn(CARD, "flex-[5_1_320px] gap-1.5 px-3 md:px-3")}>
      <h2 id="attention" className="mx-2 text-[19px] font-bold">
        {t("today.attention")}
      </h2>
      {clear ? (
        <p className="mx-2 flex items-start gap-2 py-2 font-semibold text-primary">
          <Check aria-hidden="true" className="mt-0.5 size-5 flex-none" strokeWidth={2.5} />
          {t("today.allClear")}
        </p>
      ) : (
        <ul className="flex flex-col gap-0.5">
          {lateParcels > 0 && (
            <Problem
              link={{ to: "/orders", search: { status: "SHIPPED" } }}
              tone="danger"
              icon={<Clock />}
              title={t("today.lateParcels", {
                count: lateParcels,
                shown: format.number(lateParcels),
                days: format.number(overview.lateAfterDays),
              })}
              note={t("today.lateNote")}
            />
          )}
          {stuckCod.map((stuck) => (
            <Problem
              key={stuck.courier}
              link={{ to: "/cod" }}
              tone="warning"
              icon={<CreditCard />}
              title={t("today.stuckCod", { courier: stuck.courier, amount: format.taka(stuck.amount) })}
              note={t("today.stuckNote", { days: format.number(overview.stuckAfterDays) })}
            />
          ))}
          {data.lowStock > 0 && (
            <Problem
              link={{ to: ".", search: (prev: Record<string, unknown>) => ({ ...prev, lowStock: true }) } as LinkProps}
              tone="warning"
              icon={<Package />}
              title={t("today.lowStockItems", { count: data.lowStock, shown: format.number(data.lowStock) })}
              note={t("today.lowStockAction")}
            />
          )}
        </ul>
      )}
    </section>
  );
}

function Figure({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <>
      <span className="block text-sm text-muted-foreground">{label}</span>
      <span className="block text-[25px] leading-snug font-bold md:text-[28px]">{value}</span>
      {note && <span className="block text-sm text-muted-foreground">{note}</span>}
    </>
  );
}

function Business({
  data,
  isToday,
  isNew = false,
  children,
}: {
  data: Today | undefined;
  isToday: boolean;
  isNew?: boolean;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const profit = data?.profit;
  return (
    <section aria-labelledby="business" className={cn(CARD, "flex-[7_1_420px]")}>
      <h2 id="business" className="text-[19px] font-bold">
        {t("today.business")}
      </h2>
      {data && (
        <div className="grid grid-cols-2 gap-x-5 gap-y-3.5 md:grid-cols-[repeat(auto-fit,minmax(150px,1fr))]">
          <div>
            <Figure
              label={isToday ? t("today.salesToday") : t("today.sales")}
              value={format.taka(data.confirmed.value)}
              note={isNew ? undefined : t("customers.orderCount", { count: format.number(data.confirmed.orders) })}
            />
          </div>
          {profit && !isNew && (
            <div>
              <Figure
                label={t("today.profit")}
                value={format.taka(profit.grossProfit)}
                note={t("today.profitCost", { cost: format.taka(profit.cost) })}
              />
            </div>
          )}
          <Link
            to="/cod"
            className={cn(
              "block text-foreground no-underline hover:text-foreground",
              !isNew && "max-md:col-span-2 max-md:border-t max-md:pt-2.5",
            )}
          >
            <Figure
              label={t("today.cod")}
              value={format.taka(data.cod.outstanding)}
              note={
                isNew
                  ? undefined
                  : t("today.codNote", {
                      owed: format.taka(data.cod.withCourier),
                      road: format.taka(data.cod.inTransit),
                    })
              }
            />
          </Link>
        </div>
      )}
      {profit?.costMissing && (
        <p className="rounded-lg bg-warning p-3 text-warning-foreground">{t("today.costMissing")}</p>
      )}
      {isNew && <p className="border-t pt-2.5 text-muted-foreground">{t("today.firstSaleNote")}</p>}
      {children}
    </section>
  );
}

/** The share of finished parcels that came back, as a percentage; no share of nothing. */
const returnRate = (delivered: number, returned: number) =>
  delivered + returned === 0 ? undefined : (returned / (delivered + returned)) * 100;

function Deliveries({ overview }: { overview: Overview }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { couriers, returnReasons } = overview;
  const delivered = couriers.reduce((sum, row) => sum + row.delivered, 0);
  const returned = couriers.reduce((sum, row) => sum + row.returned, 0);
  const parcels = delivered + returned;
  const rates = couriers.map((row) => returnRate(row.delivered, row.returned) ?? 0);
  const highest = couriers.length > 1 ? Math.max(...rates) : 0;
  const reasons = RETURN_KINDS.filter((kind) => (returnReasons[kind] ?? 0) > 0);
  return (
    <section aria-labelledby="delivery" className={cn(CARD, "flex-[7_1_420px]")}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
        <h2 id="delivery" className="text-[19px] font-bold">
          {t("today.delivery")}
        </h2>
        <p className="text-sm text-muted-foreground">
          {t("today.windowSummary", {
            count: parcels,
            shown: format.number(parcels),
            days: format.number(overview.windowDays),
          })}
        </p>
      </div>
      {parcels === 0 && <p className="text-muted-foreground">{t("today.noParcels")}</p>}
      {parcels > 0 && (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap justify-between gap-x-4 gap-y-0.5">
            <p className="text-[15px]">
              <span className="text-2xl font-bold text-primary">{format.percent((delivered / parcels) * 100)}</span>{" "}
              {t("today.deliveredShare")}
            </p>
            <p className="text-[15px]">
              <span className="text-2xl font-bold text-warning-foreground">
                {format.percent((returned / parcels) * 100)}
              </span>{" "}
              {t("today.returnedShare")}
            </p>
          </div>
          <div aria-hidden="true" className="flex h-3 gap-0.75 overflow-hidden rounded-md">
            <div className="bg-primary" style={{ flexGrow: delivered, flexBasis: 0 }} />
            <div className="bg-marigold" style={{ flexGrow: returned, flexBasis: 0 }} />
          </div>
        </div>
      )}
      {couriers.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-right text-[15px]">
            <thead>
              <tr className="text-[13px] text-muted-foreground">
                <th scope="col" className="py-1.5 text-left font-semibold">
                  {t("today.courier")}
                </th>
                <th scope="col" className="px-1 py-1.5 font-semibold md:px-2">
                  {t("today.onTheWay")}
                </th>
                <th scope="col" className="px-1 py-1.5 font-semibold md:px-2">
                  {t("today.deliveredColumn")}
                </th>
                <th scope="col" className="px-1 py-1.5 font-semibold md:px-2">
                  {t("orderStatus.RETURNED")}
                </th>
                <th scope="col" className="py-1.5 pl-1 font-semibold md:pl-2">
                  {t("today.returnRate")}
                </th>
              </tr>
            </thead>
            <tbody>
              {couriers.map((row, index) => {
                const rate = returnRate(row.delivered, row.returned);
                return (
                  <tr key={row.courier} className="border-t">
                    <th scope="row" className="py-2.25 text-left font-semibold">
                      {row.courier}
                    </th>
                    <td className="px-1 py-2.25 md:px-2">{format.number(row.onTheWay)}</td>
                    <td className="px-1 py-2.25 md:px-2">{format.number(row.delivered)}</td>
                    <td className="px-1 py-2.25 md:px-2">{format.number(row.returned)}</td>
                    <td
                      className={cn(
                        "py-2.25 pl-1 md:pl-2",
                        highest > 0 && rates[index] === highest && "font-bold text-warning-foreground",
                      )}
                    >
                      {rate === undefined ? "–" : format.percent(rate)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {reasons.length > 0 && (
        <div className="flex flex-col gap-1 border-t pt-3 text-sm">
          <h3 className="text-[15px] font-semibold">{t("today.whyBack")}</h3>
          {reasons.map((kind) => (
            <p key={kind} className="flex justify-between gap-3">
              <span className="text-[#2B342F]">{t(`orders.returnKind.${kind}`)}</span>
              <span className="font-semibold">{format.number(returnReasons[kind] ?? 0)}</span>
            </p>
          ))}
        </div>
      )}
    </section>
  );
}

function Products({ data, isToday }: { data: Today; isToday: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const seesStock = can(useBusiness(), "STOCK");
  const { data: lowStock, isSuccess } = useQuery({ ...lowStockQuery({ size: SHOWN }), enabled: seesStock });
  const items = lowStock?.items;
  return (
    <section aria-labelledby="products" className={cn(CARD, "flex-[5_1_320px]")}>
      <h2 id="products" className="text-[19px] font-bold">
        {t("nav.products")}
      </h2>
      <div className="flex flex-col">
        <h3 className="pb-1.5 text-[15px] font-semibold">{isToday ? t("today.bestToday") : t("today.topProducts")}</h3>
        {data.topProducts.length === 0 && <p className="text-muted-foreground">{t("today.noSales")}</p>}
        <ul className="flex flex-col">
          {data.topProducts.slice(0, SHOWN).map((product) => (
            <li key={product.productName} className="flex items-center justify-between gap-3 border-t py-2">
              <span className="min-w-0">
                <span className="block truncate text-[15px] font-semibold">{product.productName}</span>
                <span className="block text-sm text-muted-foreground">
                  {t("today.sold", { count: format.number(product.quantity) })}
                </span>
              </span>
              <span className="flex-none text-[15px] font-semibold">{format.taka(product.value)}</span>
            </li>
          ))}
        </ul>
      </div>
      <div className="flex flex-col">
        <h3 className="pb-1.5 text-[15px] font-semibold">{t("today.runningLow")}</h3>
        {isSuccess && items?.length === 0 && <p className="text-muted-foreground">{t("products.lowStockNone")}</p>}
        <ul className="flex flex-col">
          {items?.map((item) => (
            <li key={item.variantId} className="border-t">
              <Link
                to="/products/$productId"
                params={{ productId: item.productId }}
                className="flex items-center justify-between gap-3 py-2 text-foreground no-underline hover:text-foreground"
              >
                <span className="min-w-0">
                  <span className="block truncate text-[15px] font-semibold">{item.productName}</span>
                  <span className="block text-sm text-muted-foreground">{item.label || item.sku}</span>
                </span>
                <span className="flex-none text-[15px] font-bold text-warning-foreground">
                  {t("products.available", { count: format.number(item.available) })}
                </span>
              </Link>
            </li>
          ))}
        </ul>
        {lowStock && lowStock.totalItems > SHOWN && (
          <LowStockLink className="flex min-h-11 w-fit items-center">{t("today.seeAll")}</LowStockLink>
        )}
      </div>
    </section>
  );
}

function Step({
  mark,
  done,
  current,
  title,
  note,
  link,
}: {
  mark: string;
  done?: boolean;
  current?: boolean;
  title: string;
  note: string;
  link?: LinkProps;
}) {
  const body = (
    <>
      <span
        aria-hidden="true"
        className={cn(
          "flex size-8.5 flex-none items-center justify-center rounded-full font-bold",
          done && "bg-accent text-primary",
          current && "bg-primary text-primary-foreground",
          !done && !current && "border-[1.5px] border-input text-muted-foreground",
        )}
      >
        {done ? <Check className="size-4.5" strokeWidth={2.5} /> : mark}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block font-semibold">{title}</span>
        <span className={cn("block text-sm", done ? "font-semibold text-primary" : "text-muted-foreground")}>
          {note}
        </span>
      </span>
      {link && <ChevronRight aria-hidden="true" className="size-4.5 flex-none text-muted-foreground" />}
    </>
  );
  const row = "flex min-h-17 items-center gap-3 text-foreground no-underline hover:text-foreground";
  return (
    <li className="border-t">
      {link ? (
        <Link {...link} className={row}>
          {body}
        </Link>
      ) : (
        <div className={row}>{body}</div>
      )}
    </li>
  );
}

/** A shop with no orders yet: what to do first, for those who can do it. */
function GetStarted({ overview }: { overview: Overview }) {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const canWrite = useCanWrite();
  if (!canWrite || !can(business, "PRODUCTS")) {
    return null;
  }
  return (
    <section aria-labelledby="start" className={cn(CARD, "gap-1")}>
      <h2 id="start" className="text-xl font-bold">
        {t("today.start")}
      </h2>
      <p className="mb-2 text-[15px] text-muted-foreground">{t("today.startNote")}</p>
      <ol className="flex flex-col">
        <Step mark={format.number(1)} done title={t("today.stepBusiness")} note={t("today.stepDone")} />
        <Step
          mark={format.number(2)}
          done={overview.hasProducts}
          current={!overview.hasProducts}
          title={t("today.stepProduct")}
          note={overview.hasProducts ? t("today.stepDone") : t("today.stepProductNote")}
          link={overview.hasProducts ? undefined : { to: "/products/new" }}
        />
        <Step
          mark={format.number(3)}
          current={overview.hasProducts}
          title={t("today.stepOrder")}
          note={t("today.stepOrderNote")}
          link={{ to: "/orders/new" }}
        />
      </ol>
    </section>
  );
}
