import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { AddressPagination } from "@/components/layout/Pagination";
import { SearchBox } from "@/components/layout/SearchBox";
import { useFormat } from "@/lib/format";
import { businessesQuery } from "./api";
import { statusText } from "./status";
import { useAdminTexts } from "./texts";

const route = getRouteApi("/admin/");

export function BusinessesPage() {
  const text = useAdminTexts();
  const format = useFormat();
  const { q, ...paging } = route.useSearch();
  const navigate = route.useNavigate();
  const { data } = useQuery(businessesQuery(q, paging));
  return (
    <div className="flex max-w-3xl flex-col gap-3">
      <h1 className="text-2xl font-bold tracking-tight">{text.nav.businesses}</h1>
      <SearchBox
        label={text.businesses.search}
        hint={text.businesses.searchHint}
        value={q ?? ""}
        onChange={(next) => void navigate({ search: { q: next || undefined, size: paging.size }, replace: true })}
      />
      {data?.items.length === 0 && <p className="py-8 text-center text-muted-foreground">{text.businesses.none}</p>}
      {!!data?.items.length && (
        <ul className="flex flex-col rounded-xl border bg-card px-4">
          {data.items.map(({ business, subscription }) => (
            <li key={business.id} className="border-t first:border-t-0">
              <Link
                to="/admin/businesses/$businessId"
                params={{ businessId: business.id }}
                className="flex items-center justify-between gap-3 py-3 text-foreground no-underline"
              >
                <span className="min-w-0">
                  <span className="block truncate font-semibold">{business.name}</span>
                  <span className="block truncate text-sm text-muted-foreground">
                    {business.owner
                      ? [business.owner.name, business.owner.phone && format.phone(business.owner.phone)]
                          .filter(Boolean)
                          .join(" · ")
                      : text.businesses.noOwner}
                  </span>
                </span>
                <span className="flex-none text-right text-sm">
                  <span className="block font-semibold">{statusText(subscription.status)}</span>
                  <span className="block text-muted-foreground">
                    {subscription.planCode} · {text.businesses.members(format.number(business.members))}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <AddressPagination result={data} />
    </div>
  );
}
