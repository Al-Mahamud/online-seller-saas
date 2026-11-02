import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import type { TFunction } from "i18next";
import { Plus } from "lucide-react";
import { lazy, Suspense, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { hasRole, useBusiness } from "@/api/business";
import { Choice } from "@/components/form/Choice";
import { BackToMore } from "@/components/layout/PageHeader";
import { Pagination } from "@/components/layout/Pagination";
import { ListSearch } from "@/components/search/ListSearch";
import { Button } from "@/components/ui/button";
import { WRITE_OFF_COLOR } from "@/features/reports/charts";
import { addDays, dhakaToday } from "@/lib/dates";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import {
  type Damage,
  type DamageRange,
  type DamageSource,
  type DamageSummary,
  damageSummaryQuery,
  damagesQuery,
} from "./api";

// With the product search it brings, this is only needed when recording.
const RecordDamageDialog = lazy(() => import("./RecordDamageDialog"));

const route = getRouteApi("/_app/damages/");
const SOURCES: (DamageSource | undefined)[] = [undefined, "SHELF", "RETURN"];
const CARD = "rounded-[14px] border bg-card";

/**
 * Goods damaged or lost on the shelf, and goods that came back damaged, over the days chosen: how many,
 * what they cost (managers and owners), and each one. Anyone in the team can record more.
 */
export function DamagesPage() {
  const { t } = useTranslation();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const business = useBusiness();
  const canSeeMoney = hasRole(business, "MANAGER");
  const [recording, setRecording] = useState(false);
  const today = dhakaToday();
  const to = filters.to ?? today;
  const from = filters.from ?? addDays(to, -29);
  const range: DamageRange = { from, to, q: filters.q };
  const summary = useQuery(damageSummaryQuery(range));
  const go = (next: Partial<typeof filters>) =>
    void navigate({ search: (previous) => ({ ...previous, ...next, page: undefined }), replace: true });
  const recordButton = (
    <Button
      className={cn(
        "h-11 gap-1.5 rounded-[10px] px-3.5 text-[15px] font-bold md:h-11.5 md:px-4.5 md:text-base",
        "max-md:bg-marigold-text max-md:text-ledger max-md:hover:bg-marigold max-md:focus-visible:ring-marigold-text",
      )}
      onClick={() => setRecording(true)}
    >
      <Plus aria-hidden="true" className="size-4.5" strokeWidth={2.5} />
      <span className="md:hidden">{t("damages.recordShort")}</span>
      <span className="max-md:hidden">{t("damages.record")}</span>
    </Button>
  );
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title, the days and the totals share one dark header. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-1 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="flex items-end justify-between gap-3 max-md:[&_a]:text-marigold-text">
          <div>
            <BackToMore />
            <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("damages.title")}</h1>
          </div>
          {recordButton}
        </div>
        <section
          aria-label={t("damages.period")}
          className="flex flex-col gap-3.5 md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:pt-4.5 md:pr-6 md:pb-5 md:pl-14 md:text-white"
        >
          <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
          <ListSearch
            list="damages"
            surface="inset"
            value={{ q: filters.q, from: filters.from, to: filters.to }}
            label={t("damages.search")}
            hint={t("damages.searchHint")}
            dates={{ anyLabel: t("search.preset.last30") }}
            onChange={(value) => go({ q: value.q, from: value.from, to: value.to })}
            onReset={() => void navigate({ search: {}, replace: true })}
          />
          {summary.data && <Totals summary={summary.data} canSeeMoney={canSeeMoney} />}
        </section>
      </div>
      <div className="flex flex-col gap-3.5 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5">
        <div className={cn("grid gap-4", canSeeMoney && "lg:grid-cols-[minmax(0,1fr)_320px] lg:items-start")}>
          <DamageList range={range} canSeeMoney={canSeeMoney} onRecord={() => setRecording(true)} />
          {canSeeMoney && summary.data && <MostLost summary={summary.data} />}
        </div>
      </div>
      {recording && (
        <Suspense fallback={null}>
          <RecordDamageDialog onClose={() => setRecording(false)} />
        </Suspense>
      )}
    </div>
  );
}

function Totals({ summary, canSeeMoney }: { summary: DamageSummary; canSeeMoney: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const days = format.number(daysIn(summary.from, summary.to));
  const before = summary.quantityBefore;
  const compared =
    before === null
      ? t("damages.daysOnly", { days })
      : before === summary.quantity
        ? t("damages.compareSame", { days })
        : t(summary.quantity > before ? "damages.compareMore" : "damages.compareLess", {
            days,
            count: format.number(Math.abs(summary.quantity - before)),
          });
  const count = (value: number) => t("damages.count", { count: format.number(value) });
  return (
    <dl
      className={cn(
        "grid grid-cols-2 gap-x-4 gap-y-2.5 border-t border-ledger-outline pt-3.5 md:gap-5",
        canSeeMoney ? "md:grid-cols-[1.4fr_1.2fr_0.8fr_0.8fr]" : "md:grid-cols-[1.6fr_1fr_1fr]",
      )}
    >
      <div className="max-md:col-span-2">
        <dt className="text-[15px] font-semibold text-ledger-text">{t("damages.damaged")}</dt>
        <dd className="text-4xl leading-tight font-bold text-marigold-text md:text-[38px]">
          {t("damages.items", { count: format.number(summary.quantity) })}
        </dd>
        <dd className="text-[15px] text-ledger-text">{compared}</dd>
      </div>
      {canSeeMoney && summary.loss !== null && (
        <div>
          <dt className="text-[15px] font-semibold text-ledger-text">{t("damages.loss")}</dt>
          <dd className="text-2xl font-bold">{format.taka(summary.loss)}</dd>
        </div>
      )}
      <div>
        <dt className="text-[15px] font-semibold text-ledger-text">{t("damages.source.SHELF")}</dt>
        <dd className="text-2xl font-bold">{count(summary.shelfQuantity)}</dd>
      </div>
      <div>
        <dt className="text-[15px] font-semibold text-ledger-text">{t("damages.source.RETURN")}</dt>
        <dd className="text-2xl font-bold">{count(summary.returnQuantity)}</dd>
      </div>
    </dl>
  );
}

function daysIn(from: string, to: string) {
  return Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000) + 1;
}

function DamageList({
  range,
  canSeeMoney,
  onRecord,
}: {
  range: DamageRange;
  canSeeMoney: boolean;
  onRecord: () => void;
}) {
  const { t } = useTranslation();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const top = useRef<HTMLElement>(null);
  const list = useQuery(damagesQuery(range, { source: filters.source, page: filters.page, size: filters.size }));
  const rows = list.data?.items ?? [];
  const searching = !!range.q || !!filters.source;
  return (
    <section ref={top} aria-labelledby="damage-list" className={cn(CARD, "scroll-mt-4 overflow-hidden")}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 px-3.5 pt-3 pb-2.5 md:px-5 md:pt-3.5 md:pb-3">
        <h2 id="damage-list" className="text-[21px] font-bold">
          {t("damages.listTitle")}
        </h2>
        {/* biome-ignore lint/a11y/useSemanticElements: three buttons that belong together, with no form around them. */}
        <div role="group" aria-label={t("damages.sourceGroup")} className="flex gap-1.5 overflow-x-auto">
          {SOURCES.map((source) => (
            <Choice
              key={source ?? "ALL"}
              on={filters.source === source}
              onClick={() =>
                void navigate({ search: (previous) => ({ ...previous, source, page: undefined }), replace: true })
              }
            >
              {t(`damages.source.${source ?? "ALL"}`)}
            </Choice>
          ))}
        </div>
      </div>
      {list.isSuccess && rows.length === 0 && (
        <div className="flex flex-col items-center gap-2.5 border-t px-4 pt-7 pb-9 text-center">
          <p className="text-[17px] font-bold">{t(searching ? "damages.noMatch" : "damages.none")}</p>
          {searching ? (
            <Button
              variant="outline"
              className="h-11 rounded-[10px] border-[1.5px] px-4"
              onClick={() =>
                void navigate({
                  search: (previous) => ({ ...previous, q: undefined, source: undefined, page: undefined }),
                  replace: true,
                })
              }
            >
              {t("damages.showAll")}
            </Button>
          ) : (
            <Button
              variant="outline"
              className="h-11 rounded-[10px] border-[1.5px] border-primary px-4 font-bold text-primary"
              onClick={onRecord}
            >
              {t("damages.record")}
            </Button>
          )}
        </div>
      )}
      {rows.length > 0 && (
        <>
          <DamageTable rows={rows} canSeeMoney={canSeeMoney} />
          <ul className="md:hidden">
            {rows.map((damage) => (
              <DamageCard key={damage.id} damage={damage} canSeeMoney={canSeeMoney} />
            ))}
          </ul>
          <div className="border-t px-3.5 pt-3 pb-3.5 md:px-5">
            <Pagination
              result={list.data}
              scrollTo={top}
              onChange={(paging, { replace }) =>
                void navigate({ search: (previous) => ({ ...previous, ...paging }), replace })
              }
            />
          </div>
        </>
      )}
    </section>
  );
}

function SourceBadge({ damage }: { damage: Damage }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <>
      <span
        className={cn(
          "inline-block rounded-full px-2.5 py-0.5 text-sm font-semibold whitespace-nowrap",
          damage.source === "SHELF" ? "bg-muted text-foreground" : "bg-warning text-warning-foreground",
        )}
      >
        {t(`damages.source.${damage.source}`)}
      </span>
      {damage.orderId && damage.orderNumber && (
        <Link
          to="/orders/$orderId"
          params={{ orderId: damage.orderId }}
          className="block min-h-6 text-sm text-primary underline-offset-2 hover:underline"
        >
          {t("damages.order", { number: format.orderNumber(damage.orderNumber) })}
        </Link>
      )}
    </>
  );
}

/** The picked reason on the shelf; a return's own reason is its note. */
function reasonText(damage: Damage, t: TFunction) {
  return damage.reason ? t(`damages.reason.${damage.reason}`) : damage.source === "SHELF" ? t("damages.noReason") : "";
}

function DamageTable({ rows, canSeeMoney }: { rows: Damage[]; canSeeMoney: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <table className="w-full border-collapse text-[15px] max-md:hidden">
      <thead>
        <tr className="bg-muted text-left text-sm text-muted-foreground">
          <th scope="col" className="py-2 pr-2.5 pl-5 font-semibold">
            {t("damages.column.date")}
          </th>
          <th scope="col" className="px-2.5 py-2 font-semibold">
            {t("damages.column.product")}
          </th>
          <th scope="col" className="px-2.5 py-2 font-semibold">
            {t("damages.column.source")}
          </th>
          <th scope="col" className="px-2.5 py-2 font-semibold">
            {t("damages.column.reason")}
          </th>
          <th scope="col" className={cn("py-2 pl-2.5 text-right font-semibold", canSeeMoney ? "pr-2.5" : "pr-5")}>
            {t("damages.column.quantity")}
          </th>
          {canSeeMoney && (
            <th scope="col" className="py-2 pr-5 pl-2.5 text-right font-semibold">
              {t("damages.column.loss")}
            </th>
          )}
        </tr>
      </thead>
      <tbody>
        {rows.map((damage) => (
          <tr key={damage.id} className="border-t align-top">
            <td className="py-2.5 pr-2.5 pl-5 whitespace-nowrap">
              {format.dateTime(damage.at)}
              {damage.recordedBy && (
                <span className="block text-[13px] text-muted-foreground">{damage.recordedBy}</span>
              )}
            </td>
            <th scope="row" className="px-2.5 py-2.5 text-left font-semibold break-words">
              {damage.productName}
              {damage.variantLabel && (
                <span className="block text-sm font-normal text-muted-foreground">{damage.variantLabel}</span>
              )}
            </th>
            <td className="px-2.5 py-2.5">
              <SourceBadge damage={damage} />
            </td>
            <td className="px-2.5 py-2.5 break-words">
              {reasonText(damage, t)}
              {damage.note && <span className="block text-sm text-muted-foreground">{damage.note}</span>}
            </td>
            <td className={cn("py-2.5 pl-2.5 text-right font-bold whitespace-nowrap", canSeeMoney ? "pr-2.5" : "pr-5")}>
              {t("damages.count", { count: format.number(damage.quantity) })}
            </td>
            {canSeeMoney && (
              <td
                className={cn(
                  "py-2.5 pr-5 pl-2.5 text-right whitespace-nowrap",
                  damage.loss === null && "text-muted-foreground",
                )}
              >
                {damage.loss === null ? t("damages.noCost") : format.taka(damage.loss)}
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function DamageCard({ damage, canSeeMoney }: { damage: Damage; canSeeMoney: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const reason = reasonText(damage, t);
  return (
    <li className="flex flex-col gap-1.5 border-t px-3.5 py-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-bold break-words">{damage.productName}</p>
          {damage.variantLabel && <p className="text-sm text-muted-foreground">{damage.variantLabel}</p>}
        </div>
        <div className="flex-none text-right">
          <p className="font-bold">{t("damages.count", { count: format.number(damage.quantity) })}</p>
          {canSeeMoney && (
            <p className="text-sm text-muted-foreground">
              {damage.loss === null ? t("damages.noCost") : format.taka(damage.loss)}
            </p>
          )}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
        <SourceBadge damage={damage} />
        {reason && <span>{reason}</span>}
        {damage.note && <span className="break-words">{damage.note}</span>}
      </div>
      <p className="text-[13px] text-muted-foreground">
        {format.dateTime(damage.at)}
        {damage.recordedBy && ` · ${damage.recordedBy}`}
      </p>
    </li>
  );
}

/** The products losing the most, with how they compare. */
function MostLost({ summary }: { summary: DamageSummary }) {
  const { t } = useTranslation();
  const format = useFormat();
  const top = summary.top ?? [];
  const highest = Math.max(1, ...top.map((product) => product.loss));
  return (
    <aside aria-labelledby="most-lost" className={cn(CARD, "px-4.5 pt-3.5 pb-4")}>
      <h2 id="most-lost" className="text-[21px] font-bold">
        {t("damages.topTitle")}
      </h2>
      <p className="mb-2.5 text-sm text-muted-foreground">{t("damages.topHint")}</p>
      {top.length > 0 && (
        <ol className="flex flex-col gap-3">
          {top.map((product) => (
            <li key={product.productId}>
              <div className="flex justify-between gap-3 text-[15px]">
                <span className="min-w-0 font-semibold break-words">{product.name}</span>
                <span className="flex-none font-bold">{format.taka(product.loss)}</span>
              </div>
              <div aria-hidden="true" className="mt-1 h-2 rounded bg-muted">
                <div
                  className="h-2 rounded"
                  style={{ width: `${Math.max(4, (product.loss / highest) * 100)}%`, background: WRITE_OFF_COLOR }}
                />
              </div>
              <p className="mt-0.5 text-[13px] text-muted-foreground">
                {t("damages.topItems", { count: format.number(product.quantity) })}
              </p>
            </li>
          ))}
        </ol>
      )}
      {(summary.withoutCost ?? 0) > 0 && (
        <p className="mt-3.5 rounded-[10px] bg-warning px-3 py-2.5 text-sm font-semibold text-warning-foreground">
          {t("damages.withoutCost", { count: format.number(summary.withoutCost ?? 0) })}{" "}
          <Link to="/products" className="text-warning-foreground underline">
            {t("nav.products")}
          </Link>
        </p>
      )}
      <p className="mt-3.5 rounded-[10px] bg-muted px-3 py-2.5 text-sm text-[#3E4842]">{t("damages.profitNote")}</p>
    </aside>
  );
}
