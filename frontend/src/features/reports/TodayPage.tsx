import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link, type LinkProps } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { errorMessages } from "@/api/query";
import { Field } from "@/components/form/Field";
import { Section } from "@/components/layout/Section";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { lowStockQuery } from "@/features/catalog/api";
import { ORDER_STATUSES } from "@/features/orders/search";
import { dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { dashboardQuery, type Today } from "./api";
import { SalesChart } from "./SalesChart";

const route = getRouteApi("/_app/");
const LOW_STOCK_SHOWN = 4;

function Tile({ label, value, note, link }: { label: string; value: string; note?: string; link?: LinkProps }) {
  const body = (
    <>
      <span className="block text-sm text-muted-foreground">{label}</span>
      <span className="block text-2xl font-bold tracking-tight md:text-[28px]">{value}</span>
      {note && <span className="block text-[13px] text-muted-foreground">{note}</span>}
    </>
  );
  const box = "block rounded-xl border bg-card p-3.5 text-foreground no-underline md:p-4.5";
  return link ? (
    <Link {...link} className={box}>
      {body}
    </Link>
  ) : (
    <div className={box}>{body}</div>
  );
}

/** The day at a glance: what sold, what needs doing, and where the money is. */
export function TodayPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const { date } = route.useSearch();
  const navigate = route.useNavigate();
  const today = dhakaToday();
  const day = date ?? today;
  const { data, error } = useQuery(dashboardQuery(date));
  const choose = (next: string) => void navigate({ search: next && next !== today ? { date: next } : {} });
  return (
    <div className="flex max-w-4xl flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight md:text-3xl">
            {day === today ? t("nav.today") : format.fullDate(day)}
          </h1>
          <p className="text-muted-foreground">
            {day === today ? format.fullDate(day) : <Link to="/">{t("today.backToToday")}</Link>}
          </p>
        </div>
        <div className="w-44">
          <Field
            label={t("today.pickDay")}
            type="date"
            max={today}
            value={day}
            onChange={(event) => choose(event.target.value)}
          />
        </div>
      </div>
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{errorMessages(error)}</AlertDescription>
        </Alert>
      )}
      {data && <Figures data={data} />}
      <SalesChart today={today} chosen={day} onChoose={choose} />
      <div className="grid items-start gap-4 md:grid-cols-2">
        {data && <ByStatus data={data} />}
        {data && <TopProducts data={data} />}
        <RunningLow />
      </div>
      <Link to="/reports" className="flex min-h-11 w-fit items-center text-primary">
        {t("today.allReports")}
      </Link>
    </div>
  );
}

function Figures({ data }: { data: Today }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { profit } = data;
  return (
    <>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        <Tile
          label={t("today.sales")}
          value={format.taka(data.confirmed.value)}
          note={t("customers.orderCount", { count: format.number(data.confirmed.orders) })}
        />
        {profit && (
          <Tile
            label={t("today.profit")}
            value={format.taka(profit.grossProfit)}
            note={t("today.profitNote", { revenue: format.taka(profit.revenue), cost: format.taka(profit.cost) })}
          />
        )}
        <Tile
          label={t("today.toPack")}
          value={format.number(data.byStatus.CONFIRMED ?? 0)}
          note={t("today.toPackNote")}
          link={{ to: "/orders", search: { status: "CONFIRMED" } }}
        />
        <Tile
          label={t("today.withCouriers")}
          value={format.number(data.byStatus.SHIPPED ?? 0)}
          note={t("today.movedNote", {
            shipped: format.number(data.shipped),
            delivered: format.number(data.delivered),
          })}
          link={{ to: "/orders", search: { status: "SHIPPED" } }}
        />
        <Tile
          label={t("today.cod")}
          value={format.taka(data.cod.outstanding)}
          note={t("today.codNote", { owed: format.taka(data.cod.withCourier), road: format.taka(data.cod.inTransit) })}
          link={{ to: "/cod" }}
        />
        <Tile
          label={t("today.lowStock")}
          value={format.number(data.lowStock)}
          note={t("today.lowStockNote")}
          link={{ to: "/products/low-stock" }}
        />
      </div>
      {profit?.costMissing && (
        <p className="rounded-lg bg-warning p-3 text-warning-foreground">{t("today.costMissing")}</p>
      )}
    </>
  );
}

function ByStatus({ data }: { data: Today }) {
  const { t } = useTranslation();
  const format = useFormat();
  const highest = Math.max(1, ...ORDER_STATUSES.map((status) => data.byStatus[status] ?? 0));
  return (
    <Section title={t("today.byStatus")}>
      <ul className="flex flex-col">
        {ORDER_STATUSES.map((status) => {
          const count = data.byStatus[status] ?? 0;
          return (
            <li key={status}>
              <Link
                to="/orders"
                search={{ status }}
                className="grid min-h-11 grid-cols-[6.5rem_minmax(0,1fr)_2.5rem] items-center gap-3 text-foreground no-underline"
              >
                <span>{t(`orderStatus.${status}`)}</span>
                <span aria-hidden="true" className="block h-3 overflow-hidden rounded-r bg-muted">
                  <span className="block h-3 rounded-r bg-primary" style={{ width: `${(count / highest) * 100}%` }} />
                </span>
                <span className="text-right font-semibold">{format.number(count)}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </Section>
  );
}

function TopProducts({ data }: { data: Today }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <Section title={t("today.topProducts")}>
      {data.topProducts.length === 0 && <p className="text-muted-foreground">{t("today.noSales")}</p>}
      <ul className="flex flex-col">
        {data.topProducts.map((product) => (
          <li
            key={product.productName}
            className="flex items-center justify-between gap-3 border-t py-2 first:border-t-0"
          >
            <span className="min-w-0">
              <span className="block truncate font-medium">{product.productName}</span>
              <span className="block text-sm text-muted-foreground">
                {t("today.sold", { count: format.number(product.quantity) })}
              </span>
            </span>
            <span className="flex-none font-semibold">{format.taka(product.value)}</span>
          </li>
        ))}
      </ul>
    </Section>
  );
}

function RunningLow() {
  const { t } = useTranslation();
  const format = useFormat();
  const { data: items, isSuccess } = useQuery(lowStockQuery);
  return (
    <Section title={t("today.runningLow")}>
      {isSuccess && items.length === 0 && <p className="text-muted-foreground">{t("products.lowStockNone")}</p>}
      <ul className="flex flex-col">
        {items?.slice(0, LOW_STOCK_SHOWN).map((item) => (
          <li key={item.variantId} className="border-t first:border-t-0">
            <Link
              to="/products/$productId"
              params={{ productId: item.productId }}
              className="flex items-center justify-between gap-3 py-2 text-foreground no-underline"
            >
              <span className="min-w-0">
                <span className="block truncate font-medium">{item.productName}</span>
                <span className="block text-sm text-muted-foreground">{item.label || item.sku}</span>
              </span>
              <span className="flex-none font-bold text-warning-foreground">
                {t("products.available", { count: format.number(item.available) })}
              </span>
            </Link>
          </li>
        ))}
      </ul>
      {items && items.length > LOW_STOCK_SHOWN && <Link to="/products/low-stock">{t("today.seeAll")}</Link>}
    </Section>
  );
}
