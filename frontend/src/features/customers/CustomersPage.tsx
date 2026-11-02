import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Phone, Plus } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { BackToMore } from "@/components/layout/PageHeader";
import { AddressPagination } from "@/components/layout/Pagination";
import { ListSearch } from "@/components/search/ListSearch";
import { Button } from "@/components/ui/button";
import { usePlaceName } from "@/features/delivery/geo";
import { useFormat } from "@/lib/format";
import { type Customer, customersQuery } from "./api";

// With the form libraries it brings, this is only needed when the dialog opens.
const CustomerDialog = lazy(() => import("./CustomerDialog"));

const route = getRouteApi("/_app/customers/");

/** Sellers look customers up by phone number, however it was written. */
export function CustomersPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const { q, ...paging } = route.useSearch();
  const navigate = route.useNavigate();
  const [creating, setCreating] = useState(false);
  const customers = useQuery(customersQuery(q ?? "", paging));
  const items = customers.data?.items ?? [];
  const total = customers.data?.totalItems ?? 0;
  const search = (text?: string) =>
    void navigate({ search: { q: text || undefined, size: paging.size }, replace: true });
  const addButton = (className: string) => (
    <Button className={className} onClick={() => setCreating(true)}>
      <Plus aria-hidden="true" strokeWidth={2.5} />
      {t("customers.new")}
    </Button>
  );
  return (
    <div className="flex w-full flex-col gap-4">
      {/* On phones the title and the panel share one dark header; from 768 px each stands alone. */}
      <div className="max-md:relative max-md:-mx-4 max-md:-mt-4 max-md:flex max-md:flex-col max-md:gap-2.5 max-md:bg-ledger max-md:pt-1 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 max-md:text-white md:contents">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:hidden" />
        <div className="max-md:[&_a]:text-marigold-text">
          <BackToMore />
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <h1 className="text-[28px] leading-tight font-bold md:text-[32px]">{t("customers.title")}</h1>
              {!q && total > 0 && (
                <p className="text-[15px] max-md:text-ledger-text md:text-muted-foreground">
                  {t("customers.total", { count: total, total: format.number(total) })}
                </p>
              )}
            </div>
            {addButton(
              "h-11.5 flex-none rounded-[10px] text-base font-bold max-md:bg-marigold-text max-md:text-ledger max-md:hover:bg-marigold",
            )}
          </div>
        </div>
        <ListSearch
          list="customers"
          label={t("customers.search")}
          hint={t("customers.searchHint")}
          value={{ q }}
          onChange={(value) => search(value.q)}
          onReset={() => search()}
        />
      </div>
      <div className="flex flex-col gap-3.5 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5">
        {customers.isSuccess && items.length === 0 && (
          <div className="flex flex-col items-center gap-2.5 rounded-[14px] border bg-card px-4 py-8 text-center">
            <p className="text-[17px] font-bold">{q ? t("customers.noMatch") : t("customers.none")}</p>
            {q ? (
              <Button
                variant="outline"
                className="h-11.5 rounded-[10px] border-[1.5px] px-4.5"
                onClick={() => search()}
              >
                {t("customers.showAll")}
              </Button>
            ) : (
              <>
                <p className="text-[15px] text-muted-foreground">{t("customers.noneHint")}</p>
                {addButton("h-11.5 rounded-[10px] px-4.5 text-base font-bold")}
              </>
            )}
          </div>
        )}
        {items.length > 0 && <CustomerRows customers={items} />}
        <AddressPagination result={customers.data} />
      </div>
      {creating && (
        <Suspense fallback={null}>
          <CustomerDialog
            onClose={() => setCreating(false)}
            onSaved={(customer) => void navigate({ to: "/customers/$customerId", params: { customerId: customer.id } })}
          />
        </Suspense>
      )}
    </div>
  );
}

function CustomerRows({ customers }: { customers: Customer[] }) {
  const { t } = useTranslation();
  const format = useFormat();
  const placeName = usePlaceName();
  const placeOf = (customer: Customer) =>
    [customer.area, customer.district].flatMap((part) => (part ? [placeName(part)] : [])).join(", ");
  return (
    <div className="overflow-hidden rounded-[14px] border bg-card">
      <table className="hidden w-full table-fixed md:table">
        <thead>
          <tr className="bg-muted text-left text-sm text-muted-foreground">
            <th scope="col" className="w-[30%] py-2.25 pr-3 pl-5 font-semibold">
              {t("customers.customerColumn")}
            </th>
            <th scope="col" className="px-3 py-2.25 font-semibold">
              {t("customers.phone")}
            </th>
            <th scope="col" className="px-3 py-2.25 font-semibold">
              {t("customers.placeColumn")}
            </th>
            <th scope="col" className="w-22 px-3 py-2.25 text-right font-semibold">
              {t("customers.orders")}
            </th>
            <th scope="col" className="w-38 py-2.25 pr-5 pl-3 text-right font-semibold">
              {t("customers.lastOrderLabel")}
            </th>
          </tr>
        </thead>
        <tbody>
          {customers.map((customer) => (
            <tr key={customer.id} className="relative border-t hover:bg-background">
              <td className="truncate py-3 pr-3 pl-5">
                {/* The link stretches over the whole row, so anywhere on it opens the customer. */}
                <Link
                  to="/customers/$customerId"
                  params={{ customerId: customer.id }}
                  className="font-bold text-foreground no-underline after:absolute after:inset-0"
                >
                  {customer.name} <span className="sr-only">{format.phone(customer.phone)}</span>
                </Link>
              </td>
              <td className="px-3 py-3">{format.phone(customer.phone)}</td>
              <td className="truncate px-3 py-3 text-muted-foreground">{placeOf(customer) || "–"}</td>
              <td className="px-3 py-3 text-right font-bold">
                {t("customers.countOnly", { count: format.number(customer.orderCount) })}
              </td>
              <td className="py-3 pr-5 pl-3 text-right">
                {customer.lastOrderAt ? format.date(customer.lastOrderAt) : t("customers.noOrderYet")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <ul className="md:hidden">
        {customers.map((customer) => (
          <li key={customer.id} className="relative flex items-center gap-2 border-t py-2 pr-2 pl-3.5 first:border-t-0">
            <Link
              to="/customers/$customerId"
              params={{ customerId: customer.id }}
              className="min-w-0 flex-1 text-foreground no-underline after:absolute after:inset-0"
            >
              <span className="block truncate font-bold">{customer.name}</span>
              <span className="block text-sm text-muted-foreground">{format.phone(customer.phone)}</span>
              <span className="block text-sm text-muted-foreground">
                {customer.orderCount > 0
                  ? [
                      t("customers.orderCount", { count: format.number(customer.orderCount) }),
                      customer.lastOrderAt && t("customers.lastOrder", { date: format.date(customer.lastOrderAt) }),
                    ]
                      .filter(Boolean)
                      .join(" · ")
                  : t("customers.noOrders")}
              </span>
            </Link>
            <a
              href={`tel:${customer.phone}`}
              aria-label={t("customers.callName", { name: customer.name })}
              className="relative z-1 flex size-11.5 flex-none items-center justify-center rounded-[10px] bg-accent text-accent-foreground"
            >
              <Phone aria-hidden="true" className="size-5" />
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
