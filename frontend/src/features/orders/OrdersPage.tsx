import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { ChevronRight, Plus, Printer, Search, X } from "lucide-react";
import { lazy, Suspense, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { Field } from "@/components/form/Field";
import { LoadMore } from "@/components/layout/LoadMore";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useCanWrite } from "@/features/billing/status";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type BulkResult, type OrderSummary, orderCountsQuery, ordersChanged, ordersQuery, slipsUrl } from "./api";
import { StatusBadge } from "./StatusBadge";
import { ORDER_STATUSES, type OrderFilters } from "./search";

// Only when used: it brings the dialog library, which the list otherwise doesn't need.
const BulkShipDialog = lazy(() => import("./BulkShipDialog"));

const route = getRouteApi("/_app/orders/");
const MAX_BULK = 100;
const ON_DARK_FOCUS = "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text";

function StatusTabs({ filters, onChange }: { filters: OrderFilters; onChange: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const { data: counts } = useQuery(orderCountsQuery(filters));
  const all = counts ? Object.values(counts).reduce((sum, count) => sum + count, 0) : undefined;
  const tabs = [
    { status: undefined, label: t("orders.all"), count: all },
    ...ORDER_STATUSES.map((status) => ({ status, label: t(`orderStatus.${status}`), count: counts?.[status] })),
  ];
  return (
    <nav
      aria-label={t("orders.byStatus")}
      className="-mx-3.5 flex flex-none gap-1.5 overflow-x-auto px-3.5 pb-0.5 md:mx-0 md:gap-0.5 md:border-b md:px-3 md:pb-0"
    >
      {tabs.map((tab) => {
        const active = tab.status === filters.status;
        return (
          <Link
            key={tab.status ?? "all"}
            from="/orders/"
            search={(previous) => ({ ...previous, status: tab.status })}
            replace
            onClick={onChange}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex min-h-11 flex-none items-center gap-1.5 rounded-full border-[1.5px] px-3 text-[15px] whitespace-nowrap no-underline md:min-h-13 md:rounded-none md:border-0 md:border-b-[3px] md:px-2.5",
              active
                ? "border-primary bg-primary font-bold text-primary-foreground hover:text-primary-foreground md:bg-transparent md:text-accent-foreground md:hover:text-accent-foreground"
                : "border-input bg-card text-[#2B342F] hover:text-accent-foreground md:border-transparent",
            )}
          >
            {tab.label}
            {tab.count !== undefined && (
              <span
                className={cn(
                  "text-[13px] font-bold md:min-w-5 md:rounded-full md:px-1.5 md:text-center md:font-semibold",
                  active ? "md:bg-primary md:text-primary-foreground" : "md:bg-muted",
                )}
              >
                {format.number(tab.count)}
              </span>
            )}
          </Link>
        );
      })}
    </nav>
  );
}

/** What's typed and picked waits here; the list follows the address, which changes on "search". */
function SearchPanel({ onChange }: { onChange: () => void }) {
  const { t } = useTranslation();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const [q, setQ] = useState(filters.q ?? "");
  const [from, setFrom] = useState(filters.from ?? "");
  const [to, setTo] = useState(filters.to ?? "");
  // The address can change without this panel: a chip removed, the browser's back button, a shared link.
  useEffect(() => {
    setQ(filters.q ?? "");
    setFrom(filters.from ?? "");
    setTo(filters.to ?? "");
  }, [filters.q, filters.from, filters.to]);
  return (
    // biome-ignore lint/a11y/useSemanticElements: a form with this role is how a search with a button is announced.
    <form
      role="search"
      aria-label={t("orders.search")}
      className="flex flex-col gap-2.5 md:relative md:gap-3 md:overflow-hidden md:rounded-2xl md:bg-ledger md:py-4.5 md:pr-6 md:pl-14 md:text-white"
      onSubmit={(event) => {
        event.preventDefault();
        onChange();
        void navigate({
          search: { status: filters.status, q: q.trim() || undefined, from: from || undefined, to: to || undefined },
          replace: true,
        });
      }}
    >
      <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
      <div className="relative">
        <Search
          aria-hidden="true"
          className="absolute top-1/2 left-3.5 size-5 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          type="search"
          aria-label={t("orders.search")}
          placeholder={t("orders.searchHint")}
          className={cn(
            "h-12.5 rounded-xl border-0 bg-card pl-11 text-base text-foreground md:h-13 md:text-[17px]",
            ON_DARK_FOCUS,
          )}
          value={q}
          onChange={(event) => setQ(event.target.value)}
        />
      </div>
      <div className="flex flex-wrap items-end gap-2.5 md:gap-3">
        <div className="grid min-w-0 flex-[2_1_100%] grid-cols-2 gap-2.5 md:flex-[2_1_340px] md:gap-3 [&_input]:h-11.5 [&_input]:rounded-[10px] [&_input]:border-0 [&_input]:bg-card [&_input]:px-2 [&_input]:text-[15px] [&_input]:text-foreground md:[&_input]:h-11 [&_label]:text-sm [&_label]:text-ledger-text">
          <Field label={t("orders.from")} type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
          <Field label={t("orders.to")} type="date" value={to} onChange={(event) => setTo(event.target.value)} />
        </div>
        <div className="grid flex-[1_1_100%] grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-2 md:ml-auto md:flex md:flex-none">
          <button
            type="button"
            className={cn(
              "h-12 cursor-pointer rounded-[10px] border-[1.5px] border-ledger-text text-base font-semibold text-white hover:bg-white/10 md:h-11 md:px-4 md:text-[15px]",
              ON_DARK_FOCUS,
            )}
            onClick={() => {
              onChange();
              void navigate({ search: {}, replace: true });
            }}
          >
            {t("products.list.reset")}
          </button>
          <button
            type="submit"
            className={cn(
              "flex h-12 cursor-pointer items-center justify-center gap-1.5 rounded-[10px] bg-marigold-text text-base font-bold text-ledger hover:bg-marigold md:h-11 md:px-4.5 md:text-[15px]",
              ON_DARK_FOCUS,
            )}
          >
            <Search aria-hidden="true" className="size-4.5" strokeWidth={2.5} />
            {t("products.list.searchButton")}
          </button>
        </div>
      </div>
    </form>
  );
}

/** What the list is searching for, each with a way to take it off. */
function ActiveFilters({ onChange }: { onChange: () => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const remove = (change: Partial<OrderFilters>) => {
    onChange();
    void navigate({ search: { ...filters, ...change }, replace: true });
  };
  const chips = [
    filters.q && {
      key: "q",
      text: `"${filters.q}"`,
      onRemove: () => remove({ q: undefined }),
    },
    filters.from && {
      key: "from",
      text: t("orders.list.fromChip", { date: format.date(filters.from) }),
      onRemove: () => remove({ from: undefined }),
    },
    filters.to && {
      key: "to",
      text: t("orders.list.toChip", { date: format.date(filters.to) }),
      onRemove: () => remove({ to: undefined }),
    },
  ].filter((chip) => !!chip);
  if (chips.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap items-center gap-2">
      {chips.map((chip) => (
        <button
          key={chip.key}
          type="button"
          aria-label={t("products.list.removeFilter", { name: chip.text })}
          className="flex min-h-11 cursor-pointer items-center gap-1.5 rounded-full bg-accent py-1 pr-2.5 pl-3 text-sm font-semibold text-accent-foreground hover:bg-[#D5E9DF]"
          onClick={chip.onRemove}
        >
          {chip.text}
          <X aria-hidden="true" className="size-3.5" strokeWidth={2.5} />
        </button>
      ))}
    </div>
  );
}

/** Orders by status: the day's work is here, from packing to following up the couriers. */
export function OrdersPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const queryClient = useQueryClient();
  const canWrite = useCanWrite();
  const orders = useInfiniteQuery(ordersQuery(filters));
  const items = orders.data?.pages.flatMap((page) => page.items) ?? [];
  const [selected, setSelected] = useState<Map<string, OrderSummary>>(new Map());
  const [shipping, setShipping] = useState(false);
  const [results, setResults] = useState<BulkResult[]>();
  const filtered = !!(filters.q || filters.from || filters.to);

  const chosen = [...selected.values()];
  const canPack = chosen.length > 0 && chosen.every((order) => order.status === "CONFIRMED");
  const canShip =
    chosen.length > 0 && chosen.every((order) => order.status === "CONFIRMED" || order.status === "PACKED");

  function clearSelection() {
    setSelected(new Map());
    setResults(undefined);
  }

  /** What failed may no longer be in this list, so its number is kept from the selection. */
  function finished(answers: BulkResult[]) {
    setResults(
      answers.map((answer) => ({
        ...answer,
        numberLabel: answer.numberLabel ?? selected.get(answer.orderId)?.numberLabel ?? null,
      })),
    );
    setSelected(new Map());
    ordersChanged(queryClient);
  }

  const pack = useMutation({
    mutationFn: () =>
      call(api.POST("/api/v1/orders/bulk/pack", { body: { orderIds: chosen.map((order) => order.id) } })),
    onSuccess: finished,
  });

  function toggle(order: OrderSummary, on: boolean) {
    const next = new Map(selected);
    if (on && next.size < MAX_BULK) {
      next.set(order.id, order);
    } else {
      next.delete(order.id);
    }
    setSelected(next);
    setResults(undefined);
  }

  const allSelected = items.length > 0 && items.slice(0, MAX_BULK).every((order) => selected.has(order.id));
  function toggleAll(on: boolean) {
    setResults(undefined);
    setSelected(new Map(on ? items.slice(0, MAX_BULK).map((order) => [order.id, order]) : []));
  }
  const moved = results?.filter((answer) => answer.ok).length ?? 0;
  const failures = results?.filter((answer) => !answer.ok) ?? [];
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title and the panel share one dark header; from 768 px each stands alone. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-2.5 max-md:pr-4.5 max-md:pb-8 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-[30px] leading-tight font-bold md:text-[32px]">{t("nav.orders")}</h1>
          <div className="flex gap-2.5">
            <Button
              asChild
              variant="outline"
              className="h-11 rounded-[10px] border-[1.5px] text-[15px] font-semibold max-md:border-ledger-outline max-md:bg-transparent max-md:text-white md:text-base"
            >
              <Link
                to="/cod"
                className="no-underline max-md:text-white max-md:hover:bg-white/10 max-md:hover:text-white md:text-foreground"
              >
                {t("cod.title")}
              </Link>
            </Button>
            {canWrite && (
              <Button asChild className="hidden h-11 rounded-[10px] text-base font-semibold md:inline-flex">
                <Link to="/orders/new" className="text-primary-foreground no-underline hover:text-primary-foreground">
                  <Plus aria-hidden="true" strokeWidth={2.5} />
                  {t("orders.new")}
                </Link>
              </Button>
            )}
          </div>
        </div>
        <SearchPanel onChange={clearSelection} />
      </div>

      <div className="flex flex-col gap-2.5 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5 md:gap-4">
        <ActiveFilters onChange={clearSelection} />
        {results && (
          <Alert variant={failures.length > 0 ? "destructive" : "default"}>
            <AlertDescription>
              <p>{t("orders.bulkDone", { count: format.number(moved) })}</p>
              {failures.map((failure) => (
                <p key={failure.orderId}>
                  {failure.numberLabel && format.orderNumber(failure.numberLabel)}: {failure.message}
                </p>
              ))}
            </AlertDescription>
          </Alert>
        )}
        <section
          aria-label={t("orders.list.tableLabel")}
          className="flex flex-col gap-2.5 md:gap-0 md:rounded-[14px] md:border md:bg-card"
        >
          <StatusTabs filters={filters} onChange={clearSelection} />
          {chosen.length > 0 && (
            <div className="sticky top-14 z-10 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl bg-ledger px-3.5 py-2.5 text-white md:top-0 md:rounded-none md:px-5">
              <span className="w-full text-[15px] font-semibold md:mr-auto md:w-auto">
                {t("orders.selected", { count: format.number(chosen.length) })}
              </span>
              {canPack && (
                <button
                  type="button"
                  disabled={pack.isPending}
                  className={cn(
                    "h-11 flex-1 cursor-pointer rounded-[10px] bg-marigold-text px-4 text-[15px] font-bold text-ledger hover:bg-marigold md:flex-none",
                    ON_DARK_FOCUS,
                  )}
                  onClick={() => pack.mutate()}
                >
                  {t("orders.pack")}
                </button>
              )}
              {canShip && (
                <button
                  type="button"
                  className={cn(
                    "h-11 flex-1 cursor-pointer rounded-[10px] bg-marigold-text px-4 text-[15px] font-bold text-ledger hover:bg-marigold md:flex-none",
                    ON_DARK_FOCUS,
                  )}
                  onClick={() => setShipping(true)}
                >
                  {t("orders.ship")}
                </button>
              )}
              <a
                href={slipsUrl(chosen.map((order) => order.id))}
                target="_blank"
                rel="noopener"
                className={cn(
                  "flex h-11 flex-[1_1_100%] items-center justify-center gap-1.5 rounded-[10px] border-[1.5px] border-ledger-text px-3.5 text-[15px] font-semibold text-white no-underline hover:bg-white/10 hover:text-white md:flex-none",
                  ON_DARK_FOCUS,
                )}
              >
                <Printer aria-hidden="true" className="size-4.5" />
                {t("orders.printSlips")}
              </a>
            </div>
          )}
          {orders.isSuccess && items.length === 0 && (
            <div className="flex flex-col items-center gap-2.5 rounded-[14px] border bg-card px-4 py-9 text-center md:rounded-none md:border-0 md:py-12">
              <p className="text-[17px] font-semibold">{filtered ? t("orders.noMatch") : t("orders.none")}</p>
              {filtered && (
                <Button
                  variant="outline"
                  className="h-11 rounded-[10px] border-[1.5px] border-primary px-4 font-semibold text-primary hover:text-primary"
                  onClick={() => {
                    clearSelection();
                    void navigate({ search: {}, replace: true });
                  }}
                >
                  {t("products.list.reset")}
                </Button>
              )}
            </div>
          )}
          {items.length > 0 && (
            <>
              <label className="flex min-h-11 w-fit cursor-pointer items-center gap-2.5 px-0.5 text-[15px] font-semibold md:hidden">
                <input
                  type="checkbox"
                  className="size-5 accent-primary"
                  checked={allSelected}
                  onChange={(event) => toggleAll(event.target.checked)}
                />
                {t("orders.selectAll")}
              </label>
              <ul className="flex flex-col gap-2.5 md:hidden">
                {items.map((order) => (
                  <li key={order.id}>
                    <OrderCard order={order} selected={selected.has(order.id)} onToggle={(on) => toggle(order, on)} />
                  </li>
                ))}
              </ul>
              <OrderTable
                orders={items}
                selected={selected}
                allSelected={allSelected}
                onToggle={toggle}
                onToggleAll={toggleAll}
              />
            </>
          )}
        </section>
        <LoadMore query={orders} />
      </div>
      {shipping && (
        <Suspense fallback={null}>
          <BulkShipDialog
            orders={chosen}
            onDone={(answers) => {
              setShipping(false);
              finished(answers);
            }}
            onClose={() => setShipping(false)}
          />
        </Suspense>
      )}
    </div>
  );
}

/** What's left to collect, or that nothing is; a draft or a cancelled order says neither. */
function useMoneyNote() {
  const { t } = useTranslation();
  const format = useFormat();
  return (order: OrderSummary) => {
    if (order.status === "CANCELLED") {
      return "";
    }
    if (order.codAmount > 0) {
      return t("orders.list.codShort", { amount: format.taka(order.codAmount) });
    }
    return order.paymentStatus === "PAID" ? t("orders.list.paid") : "";
  };
}

function OrderCard({
  order,
  selected,
  onToggle,
}: {
  order: OrderSummary;
  selected: boolean;
  onToggle: (on: boolean) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const moneyNote = useMoneyNote();
  const number = format.orderNumber(order.numberLabel);
  return (
    <div
      className={cn(
        "relative flex items-stretch rounded-[14px] border-[1.5px]",
        selected ? "border-primary bg-accent" : "border-border bg-card",
      )}
    >
      <label className="relative z-1 flex w-11.5 flex-none cursor-pointer items-center justify-center">
        <input
          type="checkbox"
          className="size-5 accent-primary"
          aria-label={t("orders.selectOne", { number })}
          checked={selected}
          onChange={(event) => onToggle(event.target.checked)}
        />
      </label>
      <Link
        to="/orders/$orderId"
        params={{ orderId: order.id }}
        className="flex min-w-0 flex-1 flex-col gap-1 py-2.5 pr-3 text-foreground no-underline after:absolute after:inset-0 hover:text-foreground"
      >
        <span className="flex items-baseline justify-between gap-2.5 font-bold">
          <span>{number}</span>
          <span>{format.taka(order.total)}</span>
        </span>
        <span className="flex items-baseline justify-between gap-2.5">
          <span className="min-w-0 text-[15px] font-semibold">{order.customerName}</span>
          <span className="flex-none text-[13px] text-muted-foreground">{moneyNote(order)}</span>
        </span>
        <span className="flex items-center justify-between gap-2.5">
          <span className="text-[13px] text-muted-foreground">
            {t("orders.list.cardLine", { count: format.number(order.itemCount), date: format.date(order.createdAt) })}
          </span>
          <StatusBadge status={order.status} />
        </span>
      </Link>
    </div>
  );
}

function OrderTable({
  orders,
  selected,
  allSelected,
  onToggle,
  onToggleAll,
}: {
  orders: OrderSummary[];
  selected: Map<string, OrderSummary>;
  allSelected: boolean;
  onToggle: (order: OrderSummary, on: boolean) => void;
  onToggleAll: (on: boolean) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const moneyNote = useMoneyNote();
  return (
    <div className="hidden overflow-x-auto rounded-b-[14px] md:block">
      <table className="w-full min-w-200 border-collapse">
        <thead>
          <tr className="border-b bg-background text-left text-[13px] text-muted-foreground">
            <td className="w-14 py-1 pl-3">
              <label className="flex size-11 cursor-pointer items-center justify-center">
                <input
                  type="checkbox"
                  className="size-5 accent-primary"
                  aria-label={t("orders.selectAll")}
                  checked={allSelected}
                  onChange={(event) => onToggleAll(event.target.checked)}
                />
              </label>
            </td>
            <th scope="col" className="px-2.5 font-semibold">
              {t("orders.list.orderColumn")}
            </th>
            <th scope="col" className="px-2.5 font-semibold">
              {t("orders.customer")}
            </th>
            <th scope="col" className="px-2.5 text-right font-semibold">
              {t("orders.list.itemsColumn")}
            </th>
            <th scope="col" className="px-2.5 text-right font-semibold">
              {t("orders.total")}
            </th>
            <th scope="col" className="px-2.5 font-semibold">
              {t("orders.list.statusColumn")}
            </th>
            <td className="w-10" />
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => {
            const isSelected = selected.has(order.id);
            const number = format.orderNumber(order.numberLabel);
            return (
              <tr
                key={order.id}
                className={cn("relative border-b last:border-b-0", isSelected ? "bg-accent" : "hover:bg-background")}
              >
                <td className="py-1.5 pl-3">
                  <label className="relative z-1 flex size-11 cursor-pointer items-center justify-center">
                    <input
                      type="checkbox"
                      className="size-5 accent-primary"
                      aria-label={t("orders.selectOne", { number })}
                      checked={isSelected}
                      onChange={(event) => onToggle(order, event.target.checked)}
                    />
                  </label>
                </td>
                <td className="px-2.5 py-1.5">
                  {/* The link stretches over the whole row, so anywhere on it opens the order. */}
                  <Link
                    to="/orders/$orderId"
                    params={{ orderId: order.id }}
                    className="block text-foreground no-underline after:absolute after:inset-0 hover:text-foreground focus-visible:outline-none focus-visible:after:outline-3 focus-visible:after:-outline-offset-3 focus-visible:after:outline-primary"
                  >
                    <span className="block font-bold">
                      {number} <span className="sr-only">{order.customerName}</span>
                    </span>
                    <span className="block text-[13px] text-muted-foreground">{format.date(order.createdAt)}</span>
                  </Link>
                </td>
                <td className="px-2.5 py-1.5">
                  <span className="block font-semibold">{order.customerName}</span>
                  <span className="block text-[13px] text-muted-foreground">{format.phone(order.customerPhone)}</span>
                </td>
                <td className="px-2.5 py-1.5 text-right text-[15px]">
                  {t("orders.list.itemCount", { count: format.number(order.itemCount) })}
                </td>
                <td className="px-2.5 py-1.5 text-right whitespace-nowrap">
                  <span className="block font-bold">{format.taka(order.total)}</span>
                  <span className="block text-[13px] text-muted-foreground">{moneyNote(order)}</span>
                </td>
                <td className="px-2.5 py-1.5">
                  <StatusBadge status={order.status} />
                </td>
                <td className="pr-4 text-right">
                  <ChevronRight aria-hidden="true" className="ml-auto size-4.5 text-muted-foreground" />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
