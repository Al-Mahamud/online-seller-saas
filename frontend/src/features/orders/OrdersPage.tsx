import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Plus, Printer } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, call } from "@/api/client";
import { Field } from "@/components/form/Field";
import { LoadMore } from "@/components/layout/LoadMore";
import { PageHeader } from "@/components/layout/PageHeader";
import { SearchBox } from "@/components/layout/SearchBox";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type BulkResult, type OrderSummary, orderCountsQuery, ordersChanged, ordersQuery, slipsUrl } from "./api";
import { BulkShipDialog } from "./BulkShipDialog";
import { StatusBadge } from "./StatusBadge";
import { ORDER_STATUSES, type OrderFilters } from "./search";

const route = getRouteApi("/_app/orders/");
const MAX_BULK = 100;

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
      className="-mx-4 flex gap-1.5 overflow-x-auto px-4 pb-1 md:mx-0 md:flex-wrap md:px-0"
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
              "flex min-h-11 flex-none items-center gap-1.5 rounded-full border px-3.5 no-underline",
              active ? "border-foreground bg-foreground font-semibold text-background" : "bg-card text-foreground",
            )}
          >
            {tab.label}
            {tab.count !== undefined && <span>{format.number(tab.count)}</span>}
          </Link>
        );
      })}
    </nav>
  );
}

/** Orders by status: the day's work is here, from packing to following up the couriers. */
export function OrdersPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const queryClient = useQueryClient();
  const orders = useInfiniteQuery(ordersQuery(filters));
  const items = orders.data?.pages.flatMap((page) => page.items) ?? [];
  const [selected, setSelected] = useState<Map<string, OrderSummary>>(new Map());
  const [shipping, setShipping] = useState(false);
  const [results, setResults] = useState<BulkResult[]>();
  const filtered = !!(filters.q || filters.from || filters.to);
  // Open when the link has dates in it; after that it stays as the seller leaves it.
  const [datesOpen] = useState(!!(filters.from || filters.to));

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

  function change(next: Partial<OrderFilters>) {
    clearSelection();
    void navigate({ search: { ...filters, ...next }, replace: true });
  }

  const allSelected = items.length > 0 && items.slice(0, MAX_BULK).every((order) => selected.has(order.id));
  const moved = results?.filter((answer) => answer.ok).length ?? 0;
  const failures = results?.filter((answer) => !answer.ok) ?? [];
  return (
    <div className="flex max-w-3xl flex-col gap-3">
      <PageHeader title={t("nav.orders")}>
        <div className="flex gap-2">
          <Button asChild variant="outline">
            <Link to="/cod">{t("cod.title")}</Link>
          </Button>
          <Button asChild className="hidden md:inline-flex">
            <Link to="/orders/new">
              <Plus aria-hidden="true" />
              {t("orders.new")}
            </Link>
          </Button>
        </div>
      </PageHeader>
      <SearchBox
        label={t("orders.search")}
        hint={t("orders.searchHint")}
        value={filters.q ?? ""}
        onChange={(text) => change({ q: text || undefined })}
      />
      <StatusTabs filters={filters} onChange={clearSelection} />
      <details open={datesOpen || undefined}>
        <summary className="flex min-h-11 w-fit cursor-pointer items-center text-primary">{t("orders.byDate")}</summary>
        <div className="grid grid-cols-2 gap-3 sm:max-w-sm">
          <Field
            label={t("orders.from")}
            type="date"
            value={filters.from ?? ""}
            onChange={(event) => change({ from: event.target.value || undefined })}
          />
          <Field
            label={t("orders.to")}
            type="date"
            value={filters.to ?? ""}
            onChange={(event) => change({ to: event.target.value || undefined })}
          />
        </div>
      </details>

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

      {chosen.length > 0 && (
        <div className="sticky top-14 z-10 flex flex-wrap items-center gap-2 rounded-xl border bg-card p-2.5 md:top-2">
          <span className="font-semibold">{t("orders.selected", { count: format.number(chosen.length) })}</span>
          {canPack && (
            <Button size="sm" className="min-h-11" disabled={pack.isPending} onClick={() => pack.mutate()}>
              {t("orders.pack")}
            </Button>
          )}
          {canShip && (
            <Button size="sm" className="min-h-11" onClick={() => setShipping(true)}>
              {t("orders.ship")}
            </Button>
          )}
          <Button asChild size="sm" variant="outline" className="min-h-11">
            <a href={slipsUrl(chosen.map((order) => order.id))} target="_blank" rel="noopener">
              <Printer aria-hidden="true" />
              {t("orders.printSlips")}
            </a>
          </Button>
        </div>
      )}

      {orders.isSuccess && items.length === 0 && (
        <p className="py-8 text-center text-muted-foreground">{filtered ? t("orders.noMatch") : t("orders.none")}</p>
      )}
      {items.length > 0 && (
        <>
          <label className="flex min-h-11 w-fit items-center gap-3 px-3">
            <input
              type="checkbox"
              className="size-5 accent-primary"
              checked={allSelected}
              onChange={(event) => {
                setResults(undefined);
                setSelected(
                  new Map(event.target.checked ? items.slice(0, MAX_BULK).map((order) => [order.id, order]) : []),
                );
              }}
            />
            {t("orders.selectAll")}
          </label>
          <ul className="flex flex-col rounded-xl border bg-card">
            {items.map((order) => (
              <li key={order.id} className="flex items-stretch border-t first:border-t-0">
                <label className="flex min-w-11 flex-none cursor-pointer items-center justify-center pl-1">
                  <input
                    type="checkbox"
                    className="size-5 accent-primary"
                    aria-label={t("orders.selectOne", { number: format.orderNumber(order.numberLabel) })}
                    checked={selected.has(order.id)}
                    onChange={(event) => toggle(order, event.target.checked)}
                  />
                </label>
                <Link
                  to="/orders/$orderId"
                  params={{ orderId: order.id }}
                  className="flex min-w-0 flex-1 items-center justify-between gap-3 py-3 pr-4 pl-1 text-foreground no-underline"
                >
                  <span className="min-w-0">
                    <span className="block font-semibold">
                      {format.orderNumber(order.numberLabel)} ·{" "}
                      <span className="font-medium">{order.customerName}</span>
                    </span>
                    <span className="block text-sm text-muted-foreground">
                      {t("customers.orderLine", {
                        count: format.number(order.itemCount),
                        date: format.date(order.createdAt),
                      })}
                    </span>
                  </span>
                  <span className="flex flex-none flex-col items-end gap-0.5">
                    <span className="font-bold">{format.taka(order.total)}</span>
                    <StatusBadge status={order.status} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
      <LoadMore query={orders} />
      {shipping && (
        <BulkShipDialog
          orders={chosen}
          onDone={(answers) => {
            setShipping(false);
            finished(answers);
          }}
          onClose={() => setShipping(false)}
        />
      )}
    </div>
  );
}
