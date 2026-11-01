import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { type KeyboardEvent, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { BackToMore } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type CodRow, codInTransitQuery, codOwedQuery, ordersChanged } from "./api";

interface Courier {
  name: string;
  owed: CodRow[];
  inTransit: number;
}

/** The same courier however it was typed: "pathao" and "Pathao" settle together, as on the server. */
function byCourier(owed: CodRow[], inTransit: CodRow[]): Courier[] {
  const couriers = new Map<string, Courier>();
  const entry = (name: string) => {
    const key = name.toLowerCase();
    let courier = couriers.get(key);
    if (!courier) {
      courier = { name, owed: [], inTransit: 0 };
      couriers.set(key, courier);
    }
    return courier;
  };
  for (const row of owed) {
    entry(row.courier).owed.push(row);
  }
  for (const row of inTransit) {
    entry(row.courier).inTransit += row.codExpected;
  }
  return [...couriers.values()];
}

const collected = (row: CodRow) => row.codCollected ?? 0;
const owedBy = (courier: Courier) => courier.owed.reduce((sum, row) => sum + collected(row), 0);
const keyOf = (courier: Courier) => courier.name.toLowerCase();

/** What each courier owes the business, and marking its payout as received. */
export function CodPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const owed = useQuery(codOwedQuery);
  const inTransit = useQuery(codInTransitQuery);
  // The one owed the most comes first, and opens first.
  const couriers = byCourier(owed.data ?? [], inTransit.data ?? []).sort((a, b) => owedBy(b) - owedBy(a));
  const owedRows = owed.data ?? [];
  const owedTotal = owedRows.reduce((sum, row) => sum + collected(row), 0);
  const transitTotal = (inTransit.data ?? []).reduce((sum, row) => sum + row.codExpected, 0);
  const nothing = owed.isSuccess && inTransit.isSuccess && couriers.length === 0;
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title and the totals share one dark header. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-1 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="max-md:[&_a]:text-marigold-text">
          <BackToMore />
          <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("cod.title")}</h1>
        </div>
        <section
          aria-label={t("cod.totals")}
          className="md:relative md:overflow-hidden md:rounded-2xl md:bg-ledger md:pt-4.5 md:pr-6 md:pb-5 md:pl-14 md:text-white"
        >
          <div aria-hidden="true" className="absolute inset-y-0 left-7 hidden w-0.5 bg-marigold md:block" />
          {nothing && <p className="text-[17px] font-bold">{t("cod.none")}</p>}
          {couriers.length > 0 && (
            <dl className="flex flex-col gap-2.5 md:grid md:grid-cols-2 md:gap-6">
              <div>
                <dt className="text-[15px] font-semibold text-ledger-text">{t("cod.owed")}</dt>
                <dd className="text-4xl leading-tight font-bold text-marigold-text md:text-[38px]">
                  {format.taka(owedTotal)}
                </dd>
                <dd className="text-[15px] text-ledger-text">
                  {t("cod.parcels", { count: format.number(owedRows.length) })}
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2.5 border-t border-ledger-outline pt-2.5 md:block md:border-t-0 md:border-l md:pt-0 md:pl-6">
                <dt className="text-[15px] font-semibold text-ledger-text">{t("cod.inTransit")}</dt>
                <dd className="text-xl font-bold md:text-[38px] md:leading-tight">{format.taka(transitTotal)}</dd>
                <dd className="text-[15px] text-ledger-text max-md:hidden">{t("cod.transitHint")}</dd>
              </div>
            </dl>
          )}
        </section>
      </div>
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5">
        {couriers.length > 0 && <CourierTabs couriers={couriers} />}
      </div>
    </div>
  );
}

/** One courier at a time. The others stay mounted, hidden, so what's unticked waits for coming back. */
function CourierTabs({ couriers }: { couriers: Courier[] }) {
  const { t } = useTranslation();
  const format = useFormat();
  const baseId = useId();
  const [chosenKey, setChosenKey] = useState<string>();
  // A courier that's fully settled has gone from the list; then the next one opens.
  const activeKey = couriers.some((courier) => keyOf(courier) === chosenKey) ? chosenKey : couriers.map(keyOf)[0];
  const tabId = (key: string) => `${baseId}-tab-${key}`;
  const panelId = (key: string) => `${baseId}-panel-${key}`;
  function move(event: KeyboardEvent, from: number) {
    const last = couriers.length - 1;
    const to = {
      ArrowRight: from === last ? 0 : from + 1,
      ArrowLeft: from === 0 ? last : from - 1,
      Home: 0,
      End: last,
    }[event.key];
    const next = to === undefined ? undefined : couriers[to];
    if (next) {
      event.preventDefault();
      setChosenKey(keyOf(next));
      document.getElementById(tabId(keyOf(next)))?.focus();
    }
  }
  return (
    <div className="flex flex-col gap-3.5 md:gap-0 md:overflow-hidden md:rounded-[14px] md:border md:bg-card">
      <div
        role="tablist"
        aria-label={t("cod.tabs")}
        className="flex gap-2 overflow-x-auto max-md:-mx-3.5 max-md:px-3.5 max-md:pb-0.5 md:gap-1 md:border-b md:px-3 md:pt-1.5"
      >
        {couriers.map((courier, index) => {
          const key = keyOf(courier);
          const selected = key === activeKey;
          return (
            <button
              key={key}
              id={tabId(key)}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={panelId(key)}
              tabIndex={selected ? 0 : -1}
              className="min-h-14.5 flex-none cursor-pointer rounded-[14px] border-[1.5px] bg-card px-4 py-1.25 text-left text-foreground focus-visible:outline-3 focus-visible:-outline-offset-3 focus-visible:outline-ring aria-selected:border-primary aria-selected:bg-primary aria-selected:text-primary-foreground md:min-h-15.5 md:min-w-33 md:rounded-none md:border-0 md:border-b-[3px] md:border-transparent md:bg-transparent md:px-3.5 md:hover:bg-muted md:aria-selected:border-primary md:aria-selected:bg-transparent md:aria-selected:text-accent-foreground"
              onClick={() => setChosenKey(key)}
              onKeyDown={(event) => move(event, index)}
            >
              <span className="block font-bold md:text-[17px]">{courier.name}</span>
              <span className="block text-sm font-semibold md:text-muted-foreground">
                {courier.owed.length > 0
                  ? format.taka(owedBy(courier))
                  : t("cod.onTheWay", { amount: format.taka(courier.inTransit) })}
              </span>
            </button>
          );
        })}
      </div>
      {couriers.map((courier) => (
        <CourierSection
          key={keyOf(courier)}
          courier={courier}
          id={panelId(keyOf(courier))}
          tabId={tabId(keyOf(courier))}
          hidden={keyOf(courier) !== activeKey}
        />
      ))}
    </div>
  );
}

function Amount({ row }: { row: CodRow }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <>
      <span className="block font-bold">{format.taka(collected(row))}</span>
      {collected(row) < row.codExpected && (
        <span className="block text-sm font-semibold text-warning-foreground">
          {t("cod.expected", { amount: format.taka(row.codExpected) })}
        </span>
      )}
    </>
  );
}

function CourierSection({
  courier,
  id,
  tabId,
  hidden,
}: {
  courier: Courier;
  id: string;
  tabId: string;
  hidden: boolean;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const canSettle = hasRole(business, "MANAGER");
  // Everything is ticked at first: usually the payout covers all of it. Untick what it doesn't.
  const [left, setLeft] = useState<Set<string>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const chosen = courier.owed.filter((row) => !left.has(row.id));
  const amount = chosen.reduce((sum, row) => sum + collected(row), 0);
  const allOn = chosen.length === courier.owed.length;
  const toggleAll = () => setLeft(allOn ? new Set(courier.owed.map((row) => row.id)) : new Set());
  const settle = useMutation({
    mutationFn: () =>
      call(api.POST("/api/v1/shipments/settle-cod", { body: { shipmentIds: chosen.map((row) => row.id) } })),
    onSuccess: (done) => {
      toast.success(t("cod.settled", { amount: format.taka(done.amount), courier: done.courier }));
      setLeft(new Set());
      ordersChanged(queryClient);
    },
    onSettled: () => setConfirming(false),
  });
  const tick = (row: CodRow) => (
    <input
      type="checkbox"
      className="size-5.5 accent-primary md:size-5"
      aria-label={t("orders.selectOne", { number: format.orderNumber(row.orderNumberLabel) })}
      checked={!left.has(row.id)}
      onChange={(event) => {
        const next = new Set(left);
        if (event.target.checked) {
          next.delete(row.id);
        } else {
          next.add(row.id);
        }
        setLeft(next);
      }}
    />
  );
  const orderLink = (row: CodRow) => (
    <Link to="/orders/$orderId" params={{ orderId: row.orderId }} className="font-bold text-primary">
      {format.orderNumber(row.orderNumberLabel)}
    </Link>
  );
  const owes = owedBy(courier);
  return (
    <section
      id={id}
      role="tabpanel"
      aria-labelledby={tabId}
      hidden={hidden}
      className="overflow-hidden max-md:rounded-[14px] max-md:border max-md:bg-card"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-0.5 px-3.5 py-3 md:px-5 md:py-3.5">
        <h2 className="text-xl font-bold md:text-[21px]">{courier.name}</h2>
        <p className="text-[15px] max-md:w-full">
          <span className="font-bold">
            {courier.owed.length > 0
              ? t("cod.courierOwes", { amount: format.taka(owes), count: format.number(courier.owed.length) })
              : t("cod.noneOwed")}
          </span>
          {courier.inTransit > 0 && (
            <>
              <span className="text-muted-foreground max-md:hidden"> · </span>
              <span className="text-muted-foreground max-md:block max-md:text-sm">
                {t("cod.courierInTransit", { amount: format.taka(courier.inTransit) })}
              </span>
            </>
          )}
        </p>
      </div>
      {courier.owed.length > 0 && (
        <>
          <table className="hidden w-full table-fixed md:table">
            <thead>
              <tr className="bg-muted text-left text-sm text-muted-foreground">
                {canSettle && (
                  <th scope="col" className="w-13 py-2 pl-5">
                    <input
                      type="checkbox"
                      className="block size-5 accent-primary"
                      aria-label={t("cod.selectAll", { courier: courier.name })}
                      checked={allOn}
                      onChange={toggleAll}
                    />
                  </th>
                )}
                <th scope="col" className={cn("py-2 pr-3 font-semibold", canSettle ? "pl-3" : "pl-5")}>
                  {t("orders.list.orderColumn")}
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  {t("cod.trackingColumn")}
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  {t("cod.deliveredColumn")}
                </th>
                <th scope="col" className="py-2 pr-5 pl-3 text-right font-semibold">
                  {t("cod.collectedColumn")}
                </th>
              </tr>
            </thead>
            <tbody>
              {courier.owed.map((row) => (
                <tr key={row.id} className={cn("border-t", canSettle && !left.has(row.id) && "bg-accent/40")}>
                  {canSettle && <td className="py-2 pl-5 [&_input]:block">{tick(row)}</td>}
                  <td className={cn("py-3 pr-3", canSettle ? "pl-3" : "pl-5")}>{orderLink(row)}</td>
                  <td lang="en" className="px-3 py-3">
                    {row.trackingNo}
                  </td>
                  <td className="px-3 py-3">{row.deliveredAt && format.date(row.deliveredAt)}</td>
                  <td className="py-2 pr-5 pl-3 text-right">
                    <Amount row={row} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="md:hidden">
            {canSettle && (
              <label className="flex min-h-11 cursor-pointer items-center gap-3 border-t bg-muted px-3.5 text-sm font-semibold">
                <input type="checkbox" className="size-5.5 accent-primary" checked={allOn} onChange={toggleAll} />
                {t("orders.selectAll")}
              </label>
            )}
            <ul>
              {courier.owed.map((row) => (
                <li
                  key={row.id}
                  className={cn(
                    "flex min-h-16 items-center gap-3 border-t px-3.5 py-2",
                    canSettle && !left.has(row.id) && "bg-accent/40",
                  )}
                >
                  {canSettle && tick(row)}
                  <span className="min-w-0 flex-1">
                    {orderLink(row)}
                    <span className="block text-sm text-muted-foreground">
                      {[row.trackingNo, row.deliveredAt && format.date(row.deliveredAt)].filter(Boolean).join(" · ")}
                    </span>
                  </span>
                  <span className="flex-none text-right">
                    <Amount row={row} />
                  </span>
                </li>
              ))}
            </ul>
          </div>
          {canSettle && (
            <div className="flex flex-col gap-2 border-t px-3.5 pt-2.5 pb-3.5 md:flex-row md:items-center md:justify-end md:gap-4 md:px-5 md:py-3">
              <span className="text-[15px] font-semibold md:mr-auto">
                {t("orders.selected", { count: format.number(chosen.length) })}
              </span>
              <Button
                disabled={chosen.length === 0}
                className="h-12.5 rounded-[10px] px-5 text-[17px] font-bold md:h-11.5 md:text-base"
                onClick={() => setConfirming(true)}
              >
                {t("cod.settle", { amount: format.taka(amount) })}
              </Button>
            </div>
          )}
        </>
      )}
      {confirming && (
        <ConfirmDialog
          title={t("cod.settleTitle", { courier: courier.name })}
          description={t("cod.settleDescription", { amount: format.taka(amount), count: format.number(chosen.length) })}
          confirm={t("cod.settle", { amount: format.taka(amount) })}
          pending={settle.isPending}
          onConfirm={() => settle.mutate()}
          onClose={() => setConfirming(false)}
        />
      )}
    </section>
  );
}
