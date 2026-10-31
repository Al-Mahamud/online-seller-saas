import { useInfiniteQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { lazy, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { LoadMore } from "@/components/layout/LoadMore";
import { BackToMore, PageHeader } from "@/components/layout/PageHeader";
import { SearchBox } from "@/components/layout/SearchBox";
import { Button } from "@/components/ui/button";
import { useFormat } from "@/lib/format";
import { customersQuery } from "./api";

// With the form libraries it brings, this is only needed when the dialog opens.
const CustomerDialog = lazy(() => import("./CustomerDialog"));

const route = getRouteApi("/_app/customers/");

/** Sellers look customers up by phone number, however it was written. */
export function CustomersPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const { q } = route.useSearch();
  const navigate = route.useNavigate();
  const [creating, setCreating] = useState(false);
  const customers = useInfiniteQuery(customersQuery(q ?? ""));
  const items = customers.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="flex max-w-2xl flex-col gap-3">
      <div>
        <BackToMore />
        <PageHeader title={t("customers.title")}>
          <Button onClick={() => setCreating(true)}>{t("customers.new")}</Button>
        </PageHeader>
      </div>
      <SearchBox
        label={t("customers.search")}
        hint={t("customers.searchHint")}
        value={q ?? ""}
        onChange={(text) => void navigate({ search: text ? { q: text } : {}, replace: true })}
      />
      {customers.isSuccess && items.length === 0 && (
        <p className="py-8 text-center text-muted-foreground">{q ? t("customers.noMatch") : t("customers.none")}</p>
      )}
      {items.length > 0 && (
        <ul className="flex flex-col rounded-xl border bg-card px-4">
          {items.map((customer) => (
            <li key={customer.id} className="border-t first:border-t-0">
              <Link
                to="/customers/$customerId"
                params={{ customerId: customer.id }}
                className="flex items-center justify-between gap-3 py-3 text-foreground no-underline"
              >
                <span className="min-w-0">
                  <span className="block truncate font-semibold">{customer.name}</span>
                  <span className="block text-sm text-muted-foreground">{format.phone(customer.phone)}</span>
                </span>
                <span className="flex-none text-right text-sm text-muted-foreground">
                  <span className="block">
                    {t("customers.orderCount", { count: format.number(customer.orderCount) })}
                  </span>
                  {customer.lastOrderAt && (
                    <span className="block">
                      {t("customers.lastOrder", { date: format.date(customer.lastOrderAt) })}
                    </span>
                  )}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <LoadMore query={customers} />
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
