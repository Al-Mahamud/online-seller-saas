import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { type KeyboardEvent, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { errorText } from "@/api/query";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { BackToMore } from "@/components/layout/PageHeader";
import { Pagination } from "@/components/layout/Pagination";
import { ListSearch } from "@/components/search/ListSearch";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { cn } from "@/lib/utils";
import { type CodCourier, type CodParcel, codParcelsQuery, codSummaryQuery, ordersChanged } from "./api";
import { type CodSelection, chosenTotals, EVERYTHING, isChosen, NOTHING, narrowed, toggled } from "./codSelection";

const route = getRouteApi("/_app/cod/");

const collected = (parcel: CodParcel) => parcel.codCollected ?? 0;
const keyOf = (courier: CodCourier) => courier.name.toLowerCase();

const idsIn = (value: unknown) => (Array.isArray(value) ? new Set<unknown>(value) : new Set<unknown>());

/** What each courier owes the business, found by a search if need be, and marking its payout as received. */
export function CodPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const search = { q: filters.q, from: filters.from, to: filters.to };
  const filtered = !!(search.q || search.from || search.to);
  const summary = useQuery(codSummaryQuery(search));
  const all = summary.data?.couriers ?? [];
  // A search leaves only the couriers it found something at. The server has put the one owed the most first.
  const couriers = filtered ? all.filter((courier) => courier.matchedParcels > 0) : all;
  const nothing = summary.isSuccess && all.length === 0;
  const canSearch = filtered || (summary.data?.owedParcels ?? 0) > 0;
  const resetSearch = () => void navigate({ search: { courier: filters.courier, size: filters.size }, replace: true });
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title, the totals and the search share one dark header. */}
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
          {summary.data && !nothing && (
            <dl className="flex flex-col gap-2.5 md:grid md:grid-cols-2 md:gap-6">
              <div>
                <dt className="text-[15px] font-semibold text-ledger-text">{t("cod.owed")}</dt>
                <dd className="text-4xl leading-tight font-bold text-marigold-text md:text-[38px]">
                  {format.taka(summary.data.owedAmount)}
                </dd>
                <dd className="text-[15px] text-ledger-text">
                  {t("cod.parcels", { count: format.number(summary.data.owedParcels) })}
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2.5 border-t border-ledger-outline pt-2.5 md:block md:border-t-0 md:border-l md:pt-0 md:pl-6">
                <dt className="text-[15px] font-semibold text-ledger-text">{t("cod.inTransit")}</dt>
                <dd className="text-xl font-bold md:text-[38px] md:leading-tight">
                  {format.taka(summary.data.inTransitAmount)}
                </dd>
                <dd className="text-[15px] text-ledger-text max-md:hidden">{t("cod.transitHint")}</dd>
              </div>
            </dl>
          )}
          {canSearch && (
            <ListSearch
              list="cod"
              surface="inset"
              value={search}
              label={t("cod.search")}
              hint={t("cod.searchHint")}
              dates={{}}
              className="mt-3.5 border-t border-ledger-outline pt-3.5"
              onChange={(value) =>
                void navigate({
                  search: { q: value.q, from: value.from, to: value.to, courier: filters.courier, size: filters.size },
                  replace: true,
                })
              }
              onReset={resetSearch}
            />
          )}
        </section>
      </div>
      <div className="flex flex-col gap-3.5 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5">
        {summary.isSuccess && filtered && couriers.length === 0 && (
          <div className="flex flex-col items-center gap-2.5 rounded-[14px] border bg-card px-4 py-8 text-center">
            <p className="text-[17px] font-bold">{t("cod.noMatch")}</p>
            <Button variant="outline" className="h-11.5 rounded-[10px] border-[1.5px] px-4.5" onClick={resetSearch}>
              {t("cod.showAll")}
            </Button>
          </div>
        )}
        {couriers.length > 0 && <CourierBoard couriers={couriers} filtered={filtered} />}
      </div>
    </div>
  );
}

/** One courier at a time, with its parcels a page at a time. What's unticked is for this courier and this search. */
function CourierBoard({ couriers, filtered }: { couriers: CodCourier[]; filtered: boolean }) {
  const { t } = useTranslation();
  const format = useFormat();
  const baseId = useId();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const search = { q: filters.q, from: filters.from, to: filters.to };
  const top = useRef<HTMLDivElement>(null);
  // A courier that's fully settled, or found by no search, has gone from the list; then the first one opens.
  const active = couriers.find((courier) => keyOf(courier) === filters.courier?.toLowerCase()) ?? couriers[0];
  const parcels = useQuery({
    ...codParcelsQuery(active?.name ?? "", search, { page: filters.page, size: filters.size }),
    enabled: (active?.matchedParcels ?? 0) > 0,
  });

  const [selection, setSelection] = useState<CodSelection>(EVERYTHING);
  const scope = JSON.stringify([active && keyOf(active), search]);
  const [lastScope, setLastScope] = useState(scope);
  if (scope !== lastScope) {
    setLastScope(scope);
    setSelection(EVERYTHING);
  }

  if (!active) {
    return null;
  }
  const open = (courier: CodCourier) =>
    void navigate({
      search: (previous) => ({ ...previous, courier: keyOf(courier), page: undefined }),
      replace: true,
    });
  const tabId = (courier: CodCourier) => `${baseId}-tab-${keyOf(courier)}`;
  const panelId = `${baseId}-panel`;
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
      open(next);
      document.getElementById(tabId(next))?.focus();
    }
  }
  return (
    <div ref={top} className="flex scroll-mt-4 flex-col gap-3.5">
      <div className="flex flex-col gap-3.5 md:gap-0 md:overflow-hidden md:rounded-[14px] md:border md:bg-card">
        <div
          role="tablist"
          aria-label={t("cod.tabs")}
          className="flex gap-2 overflow-x-auto max-md:-mx-3.5 max-md:px-3.5 max-md:pb-0.5 md:gap-1 md:border-b md:px-3 md:pt-1.5"
        >
          {couriers.map((courier, index) => {
            const selected = courier === active;
            return (
              <button
                key={keyOf(courier)}
                id={tabId(courier)}
                type="button"
                role="tab"
                aria-selected={selected}
                aria-controls={selected ? panelId : undefined}
                tabIndex={selected ? 0 : -1}
                className="min-h-14.5 flex-none cursor-pointer rounded-[14px] border-[1.5px] bg-card px-4 py-1.25 text-left text-foreground focus-visible:outline-3 focus-visible:-outline-offset-3 focus-visible:outline-ring aria-selected:border-primary aria-selected:bg-primary aria-selected:text-primary-foreground md:min-h-15.5 md:min-w-33 md:rounded-none md:border-0 md:border-b-[3px] md:border-transparent md:bg-transparent md:px-3.5 md:hover:bg-muted md:aria-selected:border-primary md:aria-selected:bg-transparent md:aria-selected:text-accent-foreground"
                onClick={() => open(courier)}
                onKeyDown={(event) => move(event, index)}
              >
                <span className="block font-bold md:text-[17px]">{courier.name}</span>
                <span className="block text-sm font-semibold md:text-muted-foreground">
                  {courier.matchedParcels > 0
                    ? format.taka(courier.matchedAmount)
                    : t("cod.onTheWay", { amount: format.taka(courier.inTransitAmount) })}
                </span>
              </button>
            );
          })}
        </div>
        <CourierPanel
          id={panelId}
          tabId={tabId(active)}
          courier={active}
          filtered={filtered}
          search={search}
          parcels={parcels.data?.items ?? []}
          manyPages={(parcels.data?.totalPages ?? 0) > 1}
          selection={selection}
          onSelect={setSelection}
        />
      </div>
      <Pagination
        result={parcels.data}
        scrollTo={top}
        onChange={(paging, { replace }) =>
          void navigate({ search: (previous) => ({ ...previous, ...paging }), replace })
        }
      />
    </div>
  );
}

function Amount({ parcel }: { parcel: CodParcel }) {
  const { t } = useTranslation();
  const format = useFormat();
  return (
    <>
      <span className="block font-bold">{format.taka(collected(parcel))}</span>
      {collected(parcel) < parcel.codExpected && (
        <span className="block text-sm font-semibold text-warning-foreground">
          {t("cod.expected", { amount: format.taka(parcel.codExpected) })}
        </span>
      )}
    </>
  );
}

function CourierPanel({
  id,
  tabId,
  courier,
  filtered,
  search,
  parcels,
  manyPages,
  selection,
  onSelect,
}: {
  id: string;
  tabId: string;
  courier: CodCourier;
  filtered: boolean;
  search: { q?: string; from?: string; to?: string };
  parcels: CodParcel[];
  manyPages: boolean;
  selection: CodSelection;
  onSelect: (selection: CodSelection) => void;
}) {
  const { t } = useTranslation();
  const format = useFormat();
  const business = useBusiness();
  const queryClient = useQueryClient();
  const canSettle = hasRole(business, "MANAGER");
  const allId = useId();
  const [confirming, setConfirming] = useState(false);
  const [problem, setProblem] = useState<string>();
  const found = { parcels: courier.matchedParcels, amount: courier.matchedAmount };
  const chosen = chosenTotals(selection, found);
  const allOn = chosen.parcels === found.parcels;

  const settle = useMutation({
    mutationFn: () =>
      call(
        selection.allExcept
          ? api.POST("/api/v1/shipments/settle-cod/matching", {
              body: {
                courier: courier.name,
                q: search.q,
                from: search.from,
                to: search.to,
                exceptIds: [...selection.ids.keys()],
                expectedParcels: chosen.parcels,
                expectedAmount: chosen.amount,
              },
            })
          : api.POST("/api/v1/shipments/settle-cod", { body: { shipmentIds: [...selection.ids.keys()] } }),
      ),
    meta: { showsOwnErrors: true },
    onMutate: () => setProblem(undefined),
    onSuccess: (done) => {
      toast.success(t("cod.settled", { amount: format.taka(done.amount), courier: done.courier }));
      onSelect(EVERYTHING);
      setConfirming(false);
      ordersChanged(queryClient);
    },
    onError: (error) => {
      setProblem(errorText(error));
      // The list moved under this payout: the dialog stays, with what's true now.
      if (error instanceof ApiError) {
        if (error.code === "cod.list_changed") {
          const stillLeftOut = idsIn(error.properties.exceptIds);
          onSelect(narrowed(selection, (parcel) => stillLeftOut.has(parcel)));
        } else if (error.code === "cod.not_settleable") {
          const gone = idsIn(error.properties.shipmentIds);
          onSelect(narrowed(selection, (parcel) => !gone.has(parcel)));
        }
        ordersChanged(queryClient);
      }
    },
  });

  const tick = (parcel: CodParcel) => (
    <input
      type="checkbox"
      className="size-5.5 accent-primary md:size-5"
      aria-label={t("orders.selectOne", { number: format.orderNumber(parcel.orderNumberLabel) })}
      checked={isChosen(selection, parcel.id)}
      onChange={() => onSelect(toggled(selection, parcel.id, collected(parcel)))}
    />
  );
  const tickAll = (className: string, label: { id: string } | { "aria-label": string }) => (
    <input
      type="checkbox"
      className={className}
      {...label}
      checked={allOn}
      ref={(box) => {
        if (box) {
          box.indeterminate = !allOn && chosen.parcels > 0;
        }
      }}
      onChange={() => onSelect(allOn ? NOTHING : EVERYTHING)}
    />
  );
  const allLabel = t(filtered ? "cod.selectAllFound" : "cod.selectAll");
  const orderLink = (parcel: CodParcel) => (
    <Link to="/orders/$orderId" params={{ orderId: parcel.orderId }} className="font-bold text-primary">
      {format.orderNumber(parcel.orderNumberLabel)}
    </Link>
  );
  const rowFill = (parcel: CodParcel) => canSettle && isChosen(selection, parcel.id) && "bg-accent/40";
  const counted = { total: format.number(found.parcels), chosen: format.number(chosen.parcels) };
  const settleText = t("cod.settle", { amount: format.taka(chosen.amount) });
  return (
    <section
      id={id}
      role="tabpanel"
      aria-labelledby={tabId}
      className="overflow-hidden max-md:rounded-[14px] max-md:border max-md:bg-card"
    >
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2.5 px-3.5 py-3 md:px-5 md:py-3.5">
        <div className="min-w-0 flex-[1_1_260px]">
          <h2 className="text-xl font-bold md:text-[21px]">{courier.name}</h2>
          <p className="text-[15px] font-bold">
            {found.parcels === 0
              ? t("cod.noneOwed")
              : t(filtered ? "cod.foundOwes" : "cod.courierOwes", {
                  amount: format.taka(found.amount),
                  count: format.number(found.parcels),
                })}
          </p>
          {filtered ? (
            <p className="text-sm text-muted-foreground">
              {t("cod.wholeOwes", {
                amount: format.taka(courier.owedAmount),
                count: format.number(courier.owedParcels),
              })}
            </p>
          ) : (
            courier.inTransitAmount > 0 && (
              <p className="text-sm text-muted-foreground">
                {t("cod.courierInTransit", { amount: format.taka(courier.inTransitAmount) })}
              </p>
            )
          )}
        </div>
        {canSettle && found.parcels > 0 && (
          <div className="flex flex-col gap-0.75 max-md:w-full">
            <Button
              disabled={chosen.parcels === 0}
              className="h-12.5 rounded-[10px] px-5 text-[17px] font-bold md:h-11.5 md:text-base"
              onClick={() => {
                setProblem(undefined);
                setConfirming(true);
              }}
            >
              {settleText}
            </Button>
            <p role="status" className="text-right text-sm font-semibold">
              {t(manyPages ? "cod.chosenAcrossPages" : "cod.chosen", counted)}
            </p>
          </div>
        )}
      </div>
      {parcels.length > 0 && (
        <>
          <table className="hidden w-full table-fixed md:table">
            <thead>
              <tr className="bg-muted text-left text-sm text-muted-foreground">
                {canSettle && (
                  <th scope="col" className="w-13 py-2 pl-5">
                    {tickAll("block size-5 accent-primary", { "aria-label": allLabel })}
                  </th>
                )}
                <th scope="col" className={cn("py-2 pr-3 font-semibold", canSettle ? "pl-3" : "pl-5")}>
                  {t("cod.orderColumn")}
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
              {parcels.map((parcel) => (
                <tr key={parcel.id} className={cn("border-t", rowFill(parcel))}>
                  {canSettle && <td className="py-2 pl-5 [&_input]:block">{tick(parcel)}</td>}
                  <td className={cn("py-2 pr-3", canSettle ? "pl-3" : "pl-5")}>
                    {orderLink(parcel)}
                    <span className="block truncate text-sm text-muted-foreground">{parcel.customerName}</span>
                  </td>
                  <td lang="en" className="px-3 py-2">
                    {parcel.trackingNo}
                  </td>
                  <td className="px-3 py-2">{parcel.deliveredAt && format.date(parcel.deliveredAt)}</td>
                  <td className="py-2 pr-5 pl-3 text-right">
                    <Amount parcel={parcel} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="md:hidden">
            {canSettle && (
              <label
                htmlFor={allId}
                className="flex min-h-11 cursor-pointer items-center gap-3 border-t bg-muted px-3.5 text-sm font-semibold"
              >
                {tickAll("size-5.5 accent-primary", { id: allId })}
                {allLabel}
              </label>
            )}
            <ul>
              {parcels.map((parcel) => (
                <li
                  key={parcel.id}
                  className={cn("flex min-h-16 items-center gap-3 border-t px-3.5 py-2", rowFill(parcel))}
                >
                  {canSettle && tick(parcel)}
                  <span className="min-w-0 flex-1">
                    {orderLink(parcel)}
                    <span className="block truncate text-sm text-muted-foreground">{parcel.customerName}</span>
                    <span className="block text-sm text-muted-foreground">
                      {[parcel.trackingNo, parcel.deliveredAt && format.date(parcel.deliveredAt)]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </span>
                  <span className="flex-none text-right">
                    <Amount parcel={parcel} />
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </>
      )}
      {confirming && (
        <ConfirmDialog
          title={t("cod.settleTitle", { courier: courier.name })}
          description={t(manyPages ? "cod.settleDescriptionPages" : "cod.settleDescription", {
            amount: format.taka(chosen.amount),
            count: format.number(chosen.parcels),
          })}
          problem={problem}
          confirm={settleText}
          pending={settle.isPending || chosen.parcels === 0}
          onConfirm={() => settle.mutate()}
          onClose={() => setConfirming(false)}
        />
      )}
    </section>
  );
}
