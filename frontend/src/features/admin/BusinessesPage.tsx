import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Search } from "lucide-react";
import { useEffect, useState } from "react";
import { AddressPagination } from "@/components/layout/Pagination";
import { Input } from "@/components/ui/input";
import { useFormat } from "@/lib/format";
import { AdminHero, HeroChips, PlanBadge, StateLabel, usePlanNames } from "./AdminParts";
import { type BusinessFilters, businessesQuery, metricsQuery } from "./api";
import { BUSINESS_STATES, type BusinessState } from "./search";
import { useAdminTexts } from "./texts";

const route = getRouteApi("/admin/businesses/");

export function BusinessesPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const planName = usePlanNames();
  const filters = route.useSearch();
  const navigate = route.useNavigate();
  const { data } = useQuery(businessesQuery(filters));
  const { data: metrics } = useQuery(metricsQuery);
  const [typed, setTyped] = useState(filters.q ?? "");
  useEffect(() => setTyped(filters.q ?? ""), [filters.q]);
  // A new filter starts again from the first page, keeping the rows per page.
  const go = (change: Partial<BusinessFilters>) =>
    void navigate({ search: (prev) => ({ ...prev, ...change, page: undefined }), replace: true });
  const stateCount: Partial<Record<BusinessState, number>> = metrics
    ? { GRACE: metrics.grace, READ_ONLY: metrics.readOnly, SUSPENDED: metrics.suspended }
    : {};
  const contact = (owner: { phone?: string | null; email?: string | null } | null | undefined) =>
    owner?.phone ? format.phone(owner.phone) : (owner?.email ?? "");
  return (
    <div className="flex flex-col gap-4">
      <AdminHero
        title={text.nav.businesses}
        subtitle={text.businesses.subtitle}
        aside={
          metrics && <p className="text-2xl font-bold">{text.businesses.total(format.number(metrics.businesses))}</p>
        }
      >
        <div className="flex flex-col gap-2.5 rounded-2xl bg-white/7 p-3">
          <form
            className="flex gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              go({ q: typed.trim() || undefined });
            }}
          >
            <div className="relative min-w-0 flex-1">
              <Search aria-hidden="true" className="absolute top-1/2 left-3 size-4.5 -translate-y-1/2 text-primary" />
              <Input
                type="search"
                aria-label={text.businesses.search}
                placeholder={text.businesses.searchHint}
                className="h-12 rounded-xl border-0 bg-card pl-9.5 text-base text-foreground"
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
              />
            </div>
            <button
              type="submit"
              className="h-12 flex-none cursor-pointer rounded-xl bg-marigold-text px-4.5 text-base font-bold text-ledger hover:bg-marigold"
            >
              {text.businesses.find}
            </button>
          </form>
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            <HeroChips
              label={text.businesses.plan}
              value={filters.plan}
              onChange={(plan) => go({ plan })}
              options={[
                { value: undefined, label: text.businesses.allPlans },
                ...(metrics?.plans ?? []).map((plan) => ({ value: plan.code, label: planName(plan.code) })),
              ]}
            />
            <HeroChips
              label={text.businesses.state}
              value={filters.state}
              onChange={(state) => go({ state })}
              options={[
                { value: undefined, label: text.businesses.allStates },
                ...BUSINESS_STATES.map((state) => {
                  const howMany = stateCount[state];
                  return {
                    value: state,
                    label: howMany ? `${text.state[state]} ${format.number(howMany)}` : (text.state[state] ?? state),
                  };
                }),
              ]}
            />
          </div>
        </div>
      </AdminHero>

      {data?.items.length === 0 && (
        <div className="flex flex-col items-center gap-3 rounded-2xl border bg-card px-4 py-9 text-center">
          <p className="text-[17px] font-bold">{text.businesses.none}</p>
          {(filters.q || filters.plan || filters.state) && (
            <button
              type="button"
              className="min-h-11 cursor-pointer rounded-[10px] border-[1.5px] border-primary px-4 font-bold text-primary hover:bg-accent"
              onClick={() => void navigate({ search: {}, replace: true })}
            >
              {text.businesses.seeAll}
            </button>
          )}
        </div>
      )}

      {!!data?.items.length && (
        <>
          <div className="hidden overflow-x-auto rounded-2xl border bg-card py-1.5 md:block">
            <table className="w-full min-w-190 border-collapse text-[15px]">
              <thead>
                <tr className="text-left text-sm text-muted-foreground">
                  <th scope="col" className="px-5 py-2.5 font-semibold">
                    {text.businesses.business}
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    {text.businesses.owner}
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    {text.businesses.plan}
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    {text.businesses.state}
                  </th>
                  <th scope="col" className="px-3 py-2.5 font-semibold">
                    {text.businesses.ends}
                  </th>
                  <th scope="col" className="py-2.5 pr-5 pl-3 text-right font-semibold">
                    {text.businesses.members}
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((row) => (
                  <tr key={row.business.id} className="border-t border-muted">
                    <td className="px-5 py-2.75">
                      <Link
                        to="/admin/businesses/$businessId"
                        params={{ businessId: row.business.id }}
                        className="block font-bold text-foreground no-underline hover:underline"
                      >
                        {row.business.name}
                      </Link>
                      <span className="text-[13px] text-muted-foreground">
                        {text.businesses.opened(format.date(row.business.createdAt))}
                      </span>
                    </td>
                    <td className="px-3 py-2.75">
                      <span className="block">{row.business.owner?.name ?? text.businesses.noOwner}</span>
                      <span className="text-[13px] text-muted-foreground">{contact(row.business.owner)}</span>
                    </td>
                    <td className="px-3 py-2.75">
                      {row.subscription && <PlanBadge code={row.subscription.planCode} />}
                    </td>
                    <td className="px-3 py-2.75">
                      {row.subscription && <StateLabel status={row.subscription.status} />}
                    </td>
                    <td className="px-3 py-2.75 text-foreground/80">
                      {row.subscription?.currentPeriodEnd ? format.date(row.subscription.currentPeriodEnd) : "—"}
                    </td>
                    <td className="py-2.75 pr-5 pl-3 text-right">{format.number(row.business.members)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <ul className="flex flex-col rounded-2xl border bg-card px-4 md:hidden">
            {data.items.map((row) => (
              <li key={row.business.id} className="flex flex-col gap-1 border-t py-3 first:border-t-0">
                <div className="flex items-start justify-between gap-3">
                  <Link
                    to="/admin/businesses/$businessId"
                    params={{ businessId: row.business.id }}
                    className="min-w-0 font-bold break-words text-foreground no-underline"
                  >
                    {row.business.name}
                  </Link>
                  {row.subscription && <PlanBadge code={row.subscription.planCode} />}
                </div>
                <span className="text-sm text-muted-foreground">
                  {row.business.owner
                    ? [row.business.owner.name, contact(row.business.owner)].filter(Boolean).join(" · ")
                    : text.businesses.noOwner}
                </span>
                <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                  {row.subscription && <StateLabel status={row.subscription.status} />}
                  <span>{text.businesses.opened(format.date(row.business.createdAt))}</span>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      <AddressPagination result={data} />
    </div>
  );
}
