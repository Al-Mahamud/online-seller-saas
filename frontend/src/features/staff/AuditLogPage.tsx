import { queryOptions, useQuery } from "@tanstack/react-query";
import { getRouteApi, Link, type LinkProps } from "@tanstack/react-router";
import i18next from "i18next";
import { ArrowRight, ChevronRight, Clock, Search } from "lucide-react";
import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { useMe } from "@/api/session";
import { BackToMore } from "@/components/layout/PageHeader";
import { AddressPagination } from "@/components/layout/Pagination";
import { Input } from "@/components/ui/input";
import { type LooseT, useLooseT } from "@/i18n/pages";
import { addDays, dhakaToday } from "@/lib/dates";
import { latinDigits } from "@/lib/digits";
import { useFormat } from "@/lib/format";
import { type Paging, pagedQuery, pagingSearch } from "@/lib/paging";
import { cn } from "@/lib/utils";

type Entry = components["schemas"]["AuditLogEntry"];
type Kind = Entry["kind"];
export type Period = "today" | "week" | "month" | "all";

export interface ActivityFilters extends Paging {
  actor?: string;
  kind?: Kind;
  period?: Period;
  q?: string;
}

const route = getRouteApi("/_app/settings/audit-log");
const KINDS: Kind[] = ["ORDERS", "STOCK", "PRODUCTS", "CUSTOMERS", "TEAM", "SETTINGS", "PLAN"];
const PERIODS: Period[] = ["today", "week", "month", "all"];
const DOT: Record<Kind, string> = {
  ORDERS: "bg-primary",
  STOCK: "bg-[#C4820F]",
  PRODUCTS: "bg-[#3B63B0]",
  CUSTOMERS: "bg-[#A64B86]",
  TEAM: "bg-[#6B746E]",
  SETTINGS: "bg-[#7FB3A0]",
  PLAN: "bg-marigold",
};
const TAG: Record<Kind, string> = {
  ORDERS: "bg-accent text-accent-foreground",
  STOCK: "bg-[#FDF1E7] text-[#8A4B0B]",
  PRODUCTS: "bg-[#E8EEF9] text-[#24427A]",
  CUSTOMERS: "bg-[#F6E9F2] text-[#7A2D5E]",
  TEAM: "bg-muted text-[#3E4842]",
  SETTINGS: "bg-[#E3ECE8] text-ledger",
  PLAN: "bg-[#FFF4DE] text-[#6B4A10]",
};
const MONEY_FIELDS = new Set(["price", "costPrice"]);
const AREAS = ["ORDERS", "STOCK", "CUSTOMERS", "COSTS", "PRODUCTS", "SETTINGS", "TEAM"] as const;

/** The address's filters, cleaned; the default is the last 7 days. */
export function activityFilters(search: Record<string, unknown>): ActivityFilters {
  const text = (value: unknown) => (typeof value === "string" && value.trim() ? value.trim() : undefined);
  return {
    actor: text(search.actor),
    kind: KINDS.find((kind) => kind === search.kind),
    period: PERIODS.find((period) => period === search.period),
    q: text(search.q),
    ...pagingSearch(search),
  };
}

function range(period: Period): { from?: string; to?: string } {
  const today = dhakaToday();
  return period === "today"
    ? { from: today, to: today }
    : period === "week"
      ? { from: addDays(today, -6) }
      : period === "month"
        ? { from: addDays(today, -29) }
        : {};
}

const logQuery = (filters: ActivityFilters) =>
  pagedQuery(["audit-log", filters], () =>
    call(
      api.GET("/api/v1/business/audit-log", {
        params: {
          query: {
            page: filters.page,
            size: filters.size,
            actor: filters.actor,
            kind: filters.kind,
            q: filters.q ? latinDigits(filters.q) : undefined,
            ...range(filters.period ?? "week"),
          },
        },
      }),
    ),
  );

const summaryQuery = queryOptions({
  queryKey: ["audit-log", "summary"],
  queryFn: () => call(api.GET("/api/v1/business/audit-log/summary")),
});

/** Who did what in the business, by day, newest first; owners only. */
export function AuditLogPage() {
  const t = useLooseT();
  const format = useFormat();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const me = useMe();
  const id = useId();
  const { data: summary } = useQuery(summaryQuery);
  const log = useQuery(logQuery(filters));
  const entries = log.data?.items ?? [];
  const total = log.data?.totalItems ?? 0;
  const period = filters.period ?? "week";
  const filtered = !!(filters.actor || filters.kind || filters.q);
  const [text, setText] = useState(filters.q ?? "");
  useEffect(() => setText(filters.q ?? ""), [filters.q]);
  // A new filter starts again from the first page, keeping the rows per page.
  const go = (change: Partial<ActivityFilters>) =>
    void navigate({ search: (prev) => ({ ...prev, ...change, page: undefined }), replace: true });
  const days = groupByDay(entries);
  const today = dhakaToday();
  const dayLabel = (day: string) =>
    day === today
      ? t("activity.dayToday", { date: format.fullDate(day) })
      : day === addDays(today, -1)
        ? t("activity.dayYesterday", { date: format.fullDate(day) })
        : format.fullDate(day);
  const count = (value: number) => t("activity.count", { count: format.number(value) });
  const most = Math.max(1, ...(summary?.people.map((person) => person.count) ?? [1]));
  return (
    <div className="flex flex-col gap-4">
      <header className="relative flex flex-col gap-3.5 bg-ledger pt-1 pr-3.5 pb-10.5 pl-6.5 text-white max-md:-mx-4 max-md:-mt-4 md:overflow-hidden md:rounded-2xl md:pt-4.5 md:pr-7 md:pb-5 md:pl-14">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-7" />
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="[&_a]:text-marigold-text">
            <BackToMore />
            <h1 className="text-[27px] leading-tight font-bold md:text-[32px]">{t("audit.title")}</h1>
            <p className="text-[15px] text-ledger-text">{t("activity.subtitle")}</p>
          </div>
          {summary && (
            <dl className="flex gap-5.5">
              <div>
                <dt className="text-sm font-semibold text-ledger-text">{t("activity.today")}</dt>
                <dd className="text-2xl font-bold text-marigold-text">
                  {t("activity.todayCount", { count: format.number(summary.today) })}
                </dd>
              </div>
              <div>
                <dt className="text-sm font-semibold text-ledger-text">{t("activity.week")}</dt>
                <dd className="text-2xl font-bold">{count(summary.lastSevenDays)}</dd>
              </div>
            </dl>
          )}
        </div>
        <div className="flex flex-col gap-2.5 rounded-2xl bg-white/7 p-3">
          <div className="flex flex-wrap gap-2">
            <form
              className="relative min-w-0 flex-[1_1_240px]"
              onSubmit={(event) => {
                event.preventDefault();
                go({ q: text.trim() || undefined });
              }}
            >
              <Search aria-hidden="true" className="absolute top-1/2 left-3 size-4.5 -translate-y-1/2 text-primary" />
              <Input
                type="search"
                aria-label={t("activity.search")}
                placeholder={t("activity.searchHint")}
                className="h-11.5 rounded-xl border-0 bg-card pl-9.5 text-base text-foreground"
                value={text}
                onChange={(event) => setText(event.target.value)}
                onBlur={() => text.trim() !== (filters.q ?? "") && go({ q: text.trim() || undefined })}
              />
            </form>
            <label htmlFor={`${id}-who`} className="sr-only">
              {t("activity.who")}
            </label>
            <select
              id={`${id}-who`}
              className="h-11.5 min-w-0 flex-[0_1_210px] rounded-xl bg-card px-2.5 text-[15px] text-foreground"
              value={filters.actor ?? ""}
              onChange={(event) => go({ actor: event.target.value || undefined })}
            >
              <option value="">{t("activity.everyone")}</option>
              {summary?.actors.map((actor) => (
                <option key={actor.id} value={actor.id}>
                  {actor.id === me.id ? t("activity.youOption", { name: actor.name ?? "" }) : (actor.name ?? "?")}
                  {!actor.member ? ` (${t("activity.left")})` : ""}
                </option>
              ))}
              <option value="SYSTEM">{t("activity.system")}</option>
            </select>
            {/* biome-ignore lint/a11y/useSemanticElements: four buttons that belong together, outside any form. */}
            <div role="group" aria-label={t("activity.when")} className="flex gap-1 rounded-xl bg-white/12 p-0.75">
              {PERIODS.map((option) => (
                <button
                  key={option}
                  type="button"
                  aria-pressed={period === option}
                  className={cn(
                    "h-10 cursor-pointer rounded-[9px] px-3 text-sm font-bold",
                    period === option ? "bg-card text-ledger" : "text-white hover:bg-white/10",
                  )}
                  onClick={() => go({ period: option === "week" ? undefined : option })}
                >
                  {t(`activity.period.${option}`)}
                </button>
              ))}
            </div>
          </div>
          {/* biome-ignore lint/a11y/useSemanticElements: chips that belong together, outside any form. */}
          <div role="group" aria-label={t("activity.kinds")} className="flex flex-wrap gap-1.5">
            {[undefined, ...KINDS].map((kind) => {
              const on = filters.kind === kind;
              return (
                <button
                  key={kind ?? "all"}
                  type="button"
                  aria-pressed={on}
                  className={cn(
                    "flex min-h-9 cursor-pointer items-center gap-1.5 rounded-full border-[1.5px] px-3 text-sm font-bold",
                    on ? "border-marigold bg-marigold text-ledger" : "border-white/28 text-white hover:bg-white/10",
                  )}
                  onClick={() => go({ kind })}
                >
                  <span aria-hidden="true" className={cn("size-2 rounded-full", kind ? DOT[kind] : "bg-white")} />
                  {kind ? t(`activity.kind.${kind}`) : t("activity.allKinds")}
                </button>
              );
            })}
          </div>
        </div>
      </header>
      <div className="grid items-start gap-4.5 max-md:relative max-md:-mx-4 max-md:-mt-11.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3 max-md:pt-3.5 md:grid-cols-[minmax(0,3fr)_minmax(0,1.2fr)]">
        <div className="flex min-w-0 flex-col gap-3">
          <section
            aria-labelledby={`${id}-found`}
            className="flex min-w-0 flex-col gap-1.5 rounded-2xl border bg-card p-3.5 md:p-4.5"
          >
            <p id={`${id}-found`} role="status" className="text-sm font-semibold text-muted-foreground">
              {log.isSuccess &&
                t(filtered ? "activity.foundFiltered" : "activity.found", { count: format.number(total) })}
            </p>
            {days.map(([day, items]) => (
              <div key={day}>
                <h2 className="mt-2 mb-1 flex items-baseline gap-2 font-bold">
                  {dayLabel(day)}
                  <span className="text-[13px] font-semibold text-muted-foreground">{count(items.length)}</span>
                </h2>
                <ol>
                  {items.map((entry) => (
                    <Row key={entry.id} entry={entry} myId={me.id} />
                  ))}
                </ol>
              </div>
            ))}
            {log.isSuccess && entries.length === 0 && (
              <div className="flex flex-col items-center gap-2.5 px-3 py-7 text-center">
                <span aria-hidden="true" className="flex size-13 items-center justify-center rounded-[14px] bg-accent">
                  <Clock className="size-6.5 text-primary" />
                </span>
                {filtered || period !== "all" ? (
                  <>
                    <p className="text-[17px] font-bold">{t("activity.none")}</p>
                    <button
                      type="button"
                      className="min-h-11 cursor-pointer rounded-[10px] border-[1.5px] border-primary px-4 text-[15px] font-bold text-primary hover:bg-accent"
                      onClick={() => void navigate({ search: { period: "all" }, replace: true })}
                    >
                      {t("activity.seeAll")}
                    </button>
                  </>
                ) : (
                  <>
                    <p className="text-[17px] font-bold">{t("activity.empty")}</p>
                    <p className="max-w-sm text-sm text-muted-foreground">{t("activity.emptyHint")}</p>
                  </>
                )}
              </div>
            )}
          </section>
          {entries.length > 0 && <AddressPagination result={log.data} />}
        </div>
        {summary && summary.lastSevenDays > 0 && (
          <aside
            aria-labelledby={`${id}-people`}
            className="flex flex-col gap-3.5 rounded-2xl border bg-card p-4.5 max-md:hidden"
          >
            <h2 id={`${id}-people`} className="text-lg font-bold">
              {t("activity.people")}
            </h2>
            <ul className="flex flex-col gap-1.5">
              {summary.people.map((person) => (
                <li key={person.actor?.id ?? "system"}>
                  <button
                    type="button"
                    className="flex w-full cursor-pointer flex-col gap-1 rounded-lg p-1 text-left hover:bg-muted"
                    onClick={() => go({ actor: person.actor?.id ?? "SYSTEM" })}
                  >
                    <span className="flex w-full justify-between gap-2 text-sm">
                      <span className="font-semibold">
                        {person.actor
                          ? person.actor.id === me.id
                            ? t("activity.you")
                            : person.actor.name
                          : t("activity.system")}
                      </span>
                      <strong>{count(person.count)}</strong>
                    </span>
                    <span aria-hidden="true" className="h-1.5 w-full rounded-sm bg-muted">
                      <span
                        className="block h-1.5 rounded-sm bg-primary"
                        style={{ width: `${(person.count / most) * 100}%` }}
                      />
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <h2 className="mt-1 text-lg font-bold">{t("activity.byKind")}</h2>
            <ul className="flex flex-col gap-0.5">
              {summary.kinds.map((item) => (
                <li key={item.kind}>
                  <button
                    type="button"
                    className="flex min-h-9 w-full cursor-pointer items-center gap-2 rounded-lg px-1 text-left text-sm hover:bg-muted"
                    onClick={() => go({ kind: item.kind })}
                  >
                    <span aria-hidden="true" className={cn("size-2.5 rounded-[3px]", DOT[item.kind])} />
                    <span className="flex-1">{t(`activity.kind.${item.kind}`)}</span>
                    <strong>{count(item.count)}</strong>
                  </button>
                </li>
              ))}
            </ul>
            <p className="text-[13px] text-muted-foreground">{t("activity.panelHint")}</p>
          </aside>
        )}
      </div>
    </div>
  );
}

function groupByDay(entries: Entry[]): [string, Entry[]][] {
  const days = new Map<string, Entry[]>();
  for (const entry of entries) {
    const day = dhakaToday(new Date(entry.at));
    days.set(day, [...(days.get(day) ?? []), entry]);
  }
  return [...days.entries()];
}

type Details = Record<string, unknown> | null | undefined;
interface Change {
  field: string;
  from: string;
  to: string;
}

const str = (value: unknown) => (value === null || value === undefined ? undefined : String(value));
const num = (value: unknown) => (typeof value === "number" ? value : undefined);

/** One entry as a sentence: who, what, about what (a link when it still exists), and what changed. */
function Row({ entry, myId }: { entry: Entry; myId: string }) {
  const t = useLooseT();
  const { i18n } = useTranslation();
  const format = useFormat();
  const [open, setOpen] = useState(false);
  const details = entry.details as Details;
  const actor = entry.actor;
  const who = !actor ? t("activity.system") : actor.id === myId ? t("activity.you") : (actor.name ?? "?");
  const verbKey = `activity.verb.${entry.action}`;
  const verb = i18n.exists(verbKey) ? t(verbKey) : t("activity.otherVerb", { action: entry.action });
  const subject = subjectOf(entry, details, t, format);
  const meta = metaOf(entry, details, t, format);
  const changes = changesOf(entry, details, t, format);
  const initials = !actor
    ? t("activity.system").slice(0, 2)
    : (actor.name ?? "?")
        .trim()
        .split(/\s+/)
        .slice(0, 2)
        .map((word: string) => word[0])
        .join("");
  return (
    <li className="flex gap-3 border-t py-2.5">
      <span className="w-14.5 flex-none pt-0.5 text-[13px] font-semibold text-muted-foreground md:w-18">
        {format.time(entry.at)}
      </span>
      <span
        aria-hidden="true"
        className={cn(
          "flex size-8.5 flex-none items-center justify-center rounded-[10px] text-[13px] font-bold",
          !actor ? "bg-muted text-[#3E4842]" : actor.id === myId ? "bg-marigold-text text-ledger" : TAG[entry.kind],
        )}
      >
        {initials}
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="text-[15px] leading-normal break-words">
          <strong>{who}</strong>{" "}
          {actor && !actor.member && (
            <span className="rounded-full bg-muted px-1.75 text-xs font-bold text-[#3E4842]">{t("activity.left")}</span>
          )}{" "}
          {verb}{" "}
          {subject &&
            (subject.link ? (
              <Link {...subject.link} className="font-bold">
                {subject.text}
              </Link>
            ) : (
              <strong>{subject.text}</strong>
            ))}
        </p>
        <p className="flex flex-wrap gap-x-2.5 gap-y-1 text-[13px] text-muted-foreground">
          <span className={cn("rounded-full px-2 font-bold", TAG[entry.kind])}>{t(`activity.kind.${entry.kind}`)}</span>
          {meta && <span>{meta}</span>}
        </p>
        {changes.length > 0 && (
          <>
            <button
              type="button"
              aria-expanded={open}
              className="-ml-2 flex min-h-8 w-fit cursor-pointer items-center gap-1 rounded-lg px-2 text-sm font-bold text-primary hover:bg-accent"
              onClick={() => setOpen(!open)}
            >
              <ChevronRight aria-hidden="true" className={cn("size-3.5 transition-transform", open && "rotate-90")} />
              {t("activity.changed")}
            </button>
            {open && (
              <dl className="flex flex-col gap-1.5 rounded-xl bg-background px-3 py-2.5">
                {changes.map((change) => (
                  <div
                    key={change.field}
                    className="grid gap-x-2.5 gap-y-0.5 text-sm md:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]"
                  >
                    <dt className="text-[#3E4842]">{change.field}</dt>
                    <dd className="flex flex-wrap items-center gap-1.5">
                      <span className="text-muted-foreground line-through">{change.from}</span>
                      <ArrowRight aria-hidden="true" className="size-3.5 text-muted-foreground" />
                      <span className="sr-only">{t("activity.from")}</span>
                      <strong>{change.to}</strong>
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </>
        )}
      </div>
    </li>
  );
}

type T = LooseT;
type Format = ReturnType<typeof useFormat>;

function subjectOf(
  entry: Entry,
  details: Details,
  t: T,
  format: Format,
): { text: string; link?: LinkProps } | undefined {
  const number = str(details?.number);
  if (entry.entityType === "order" && number) {
    return {
      text: t("activity.order", { number: format.orderNumber(number) }),
      link: entry.entityId ? { to: "/orders/$orderId", params: { orderId: entry.entityId } } : undefined,
    };
  }
  if (entry.entityType === "invoice" && number) {
    return {
      text: t("activity.invoice", { number: format.orderNumber(number) }),
      link: entry.entityId ? { to: "/billing/invoices/$invoiceId", params: { invoiceId: entry.entityId } } : undefined,
    };
  }
  if (entry.subject) {
    return { text: entry.subject };
  }
  switch (entry.action) {
    case "variant.prices_changed":
      return { text: str(details?.product) ?? str(details?.sku) ?? "" };
    case "stock.adjusted":
      return {
        text: t("activity.items", { count: format.number(Array.isArray(details?.lines) ? details.lines.length : 0) }),
      };
    case "customer.deleted":
      return str(details?.name) ? { text: str(details?.name) as string } : undefined;
    case "import.committed":
      return str(details?.file) ? { text: str(details?.file) as string } : undefined;
    case "cod.settled":
      return str(details?.courier) ? { text: str(details?.courier) as string } : undefined;
    default:
      return undefined;
  }
}

function metaOf(entry: Entry, details: Details, t: T, format: Format): string | undefined {
  const parts: (string | undefined)[] = [];
  switch (entry.action) {
    case "order.created":
    case "order.updated":
      parts.push(
        num(details?.total) !== undefined
          ? t("activity.total", { amount: format.taka(num(details?.total) ?? 0) })
          : undefined,
      );
      break;
    case "order.shipped":
      parts.push(str(details?.courier), str(details?.trackingNo));
      break;
    case "payment.recorded":
      parts.push(
        num(details?.amount) !== undefined ? format.taka(num(details?.amount) ?? 0) : undefined,
        str(details?.method),
      );
      break;
    case "cod.settled":
      parts.push(
        t("activity.shipments", {
          count: format.number(num(details?.shipments) ?? 0),
          amount: format.taka(num(details?.amount) ?? 0),
        }),
      );
      break;
    case "stock.adjusted":
      parts.push(str(details?.reason), str(details?.note));
      break;
    case "variant.prices_changed":
      parts.push(str(details?.product) ? str(details?.sku) : undefined);
      break;
    case "import.committed":
      parts.push(
        t("activity.imported", {
          count: format.number((num(details?.created) ?? 0) + (num(details?.updated) ?? 0)),
        }),
      );
      break;
    case "delivery_zones.changed":
      parts.push(
        num(details?.zones) !== undefined
          ? t("activity.zones", { count: format.number(num(details?.zones) ?? 0) })
          : undefined,
      );
      break;
    case "staff.added":
    case "staff.role_changed":
      parts.push(str(details?.role) ? t(`activity.role.${str(details?.role)}`) : undefined);
      break;
  }
  const text = parts.filter(Boolean).join(" · ");
  return text || undefined;
}

function changesOf(entry: Entry, details: Details, t: T, format: Format): Change[] {
  const value = (field: string, raw: unknown): string => {
    if (raw === null || raw === undefined || raw === "") {
      return t("activity.empty_value");
    }
    if (MONEY_FIELDS.has(field) && typeof raw === "number") {
      return format.taka(raw);
    }
    if (field === "stockCommitPoint" && raw === "SHIP") {
      return t("business.settings.SHIP");
    }
    if (typeof raw === "number") {
      return format.number(raw);
    }
    return String(raw);
  };
  const label = (field: string): string => {
    const key = `activity.field.${field}`;
    return i18next.exists(key) ? t(key) : field;
  };
  const changes = details?.changes;
  if (changes && typeof changes === "object") {
    return Object.entries(changes as Record<string, { from?: unknown; to?: unknown }>).map(([field, change]) => ({
      field: label(field),
      from: value(field, change?.from),
      to: value(field, change?.to),
    }));
  }
  if (entry.action === "staff.role_changed") {
    const role = (raw: unknown) => (str(raw) ? t(`activity.role.${str(raw)}`) : t("activity.empty_value"));
    return [{ field: label("role"), from: role(details?.from), to: role(details?.to) }];
  }
  if (entry.action === "business.permissions_changed") {
    const list = (raw: unknown) => (Array.isArray(raw) ? (raw as string[]) : []);
    const rows: Change[] = [];
    for (const [who, now, before] of [
      ["staff", list(details?.staff), list(details?.staffBefore)],
      ["manager", list(details?.manager), list(details?.managerBefore)],
    ] as const) {
      for (const area of AREAS) {
        if (now.includes(area) !== before.includes(area)) {
          rows.push({
            field: t("team.cell", { who: t(`team.${who}`), task: t(`team.area${area}`) }),
            from: t(before.includes(area) ? "activity.can" : "activity.cannot"),
            to: t(now.includes(area) ? "activity.can" : "activity.cannot"),
          });
        }
      }
    }
    return rows;
  }
  return [];
}
