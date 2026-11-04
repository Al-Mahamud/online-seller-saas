import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { ChevronLeft, Plus } from "lucide-react";
import { lazy, Suspense, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { can, hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { errorText } from "@/api/query";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { Pagination } from "@/components/layout/Pagination";
import { Button } from "@/components/ui/button";
import { usePlaceName } from "@/features/delivery/geo";
import { StatusBadge } from "@/features/orders/StatusBadge";
import { useFormat } from "@/lib/format";
import type { Paging } from "@/lib/paging";
import { customerOrdersQuery, customerQuery } from "./api";

// With the form libraries it brings, this is only needed when the dialog opens.
const CustomerDialog = lazy(() => import("./CustomerDialog"));

const route = getRouteApi("/_app/customers/$customerId");

const ON_DARK_BUTTON =
  "flex h-11.5 cursor-pointer items-center justify-center rounded-[10px] border-[1.5px] border-ledger-text px-3 text-[15px] font-semibold text-white no-underline hover:bg-white/10 hover:text-white focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text";

function BackToCustomers({ className }: { className?: string }) {
  const { t } = useTranslation();
  return (
    <Link
      to="/customers"
      className={`-ml-1 flex min-h-11 w-fit items-center gap-1 no-underline ${className ?? "text-primary"}`}
    >
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("customers.title")}
    </Link>
  );
}

function Stat({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div>
      <dt className="text-sm text-ledger-text">{label}</dt>
      <dd className={`text-[22px] leading-tight font-bold ${strong ? "text-marigold-text" : ""}`}>{value}</dd>
    </div>
  );
}

export function CustomerPage() {
  const { t } = useTranslation();
  const format = useFormat();
  const placeName = usePlaceName();
  const { customerId } = route.useParams();
  const business = useBusiness();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: customer } = useQuery(customerQuery(customerId));
  const [dialog, setDialog] = useState<"edit" | "delete">();
  const [problem, setProblem] = useState<string>();
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/customers/{id}", { params: { path: { id: customerId } } })),
    meta: { showsOwnErrors: true },
    onMutate: () => setProblem(undefined),
    onSuccess: async () => {
      toast.success(t("customers.deleted"));
      void queryClient.invalidateQueries({ queryKey: ["customers"] });
      await navigate({ to: "/customers" });
    },
    // Refused while they have orders in progress, which the server's message says.
    onError: (error) => setProblem(errorText(error)),
  });
  if (!customer) {
    return <BackToCustomers />;
  }
  const place = [customer.area, customer.district].flatMap((part) => (part ? [placeName(part)] : [])).join(", ");
  return (
    <div className="flex w-full flex-col gap-4">
      <div className="relative flex flex-wrap items-center justify-between gap-x-6 gap-y-3.5 overflow-hidden bg-ledger text-white max-md:-mx-4 max-md:-mt-4 max-md:pt-1 max-md:pr-4.5 max-md:pb-10.5 max-md:pl-6.5 md:rounded-2xl md:pt-3 md:pr-6 md:pb-5 md:pl-14">
        <div aria-hidden="true" className="absolute inset-y-0 left-2.5 w-0.5 bg-marigold md:left-7" />
        <div className="min-w-0 flex-[1_1_320px]">
          <BackToCustomers className="text-marigold-text hover:text-marigold-text" />
          <h1 className="text-[28px] leading-tight font-bold break-words md:text-[32px]">{customer.name}</h1>
          <p className="text-ledger-text">{format.phone(customer.phone)}</p>
          <dl className="mt-3 flex flex-wrap gap-x-7 gap-y-2">
            <Stat
              strong
              label={t("customers.orders")}
              value={t("customers.countOnly", { count: format.number(customer.orderCount) })}
            />
            <Stat
              label={t("customers.lastOrderLabel")}
              value={customer.lastOrderAt ? format.date(customer.lastOrderAt) : t("customers.noOrderYet")}
            />
            <Stat label={t("customers.since")} value={format.date(customer.createdAt)} />
          </dl>
        </div>
        <div className="flex flex-col gap-2 max-md:w-full md:w-65 md:flex-none">
          <Button
            asChild
            className="h-12 rounded-[10px] bg-marigold-text text-base font-bold text-ledger hover:bg-marigold hover:text-ledger"
          >
            <Link to="/orders/new" search={{ phone: customer.phone }} className="text-ledger no-underline">
              <Plus aria-hidden="true" strokeWidth={2.5} />
              {t("orders.new")}
            </Link>
          </Button>
          <div className="grid grid-cols-2 gap-2">
            <a href={`tel:${customer.phone}`} className={ON_DARK_BUTTON}>
              {t("customers.call")}
            </a>
            {can(business, "CUSTOMERS") && (
              <button type="button" className={ON_DARK_BUTTON} onClick={() => setDialog("edit")}>
                {t("customers.edit")}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="grid gap-4 max-md:relative max-md:-mx-4 max-md:-mt-7.5 max-md:rounded-t-3xl max-md:bg-background max-md:px-3.5 max-md:pt-3.5 lg:grid-cols-[minmax(0,1fr)_320px] lg:items-start">
        <OrdersSection phone={customer.phone} />
        <aside className="flex flex-col gap-3.5">
          <section className="flex flex-col gap-3 rounded-[14px] border bg-card px-4.5 pt-3.5 pb-4">
            <h2 className="text-[19px] font-bold">{t("customers.details")}</h2>
            <div>
              <p className="text-sm text-muted-foreground">{t("customers.phoneLabel")}</p>
              <a href={`tel:${customer.phone}`} className="block min-h-7 w-fit font-semibold text-primary">
                {format.phone(customer.phone)}
              </a>
              {customer.altPhone && (
                <a href={`tel:${customer.altPhone}`} className="block min-h-7 w-fit text-primary">
                  {format.phone(customer.altPhone)}
                </a>
              )}
            </div>
            {(customer.address || place) && (
              <div>
                <p className="text-sm text-muted-foreground">{t("customers.addressLabel")}</p>
                {customer.address && <p className="break-words whitespace-pre-wrap">{customer.address}</p>}
                {place && <p className="text-muted-foreground">{place}</p>}
              </div>
            )}
            {customer.note && (
              <div className="rounded-[10px] bg-warning px-3 py-2.5">
                <p className="text-sm font-semibold text-warning-foreground">{t("customers.noteLabel")}</p>
                <p className="text-[15px] break-words whitespace-pre-wrap">{customer.note}</p>
              </div>
            )}
          </section>
          {hasRole(business, "MANAGER") && can(business, "CUSTOMERS") && (
            <button
              type="button"
              className="min-h-11.5 w-full cursor-pointer rounded-[10px] bg-destructive px-5 text-[15px] font-bold text-white hover:bg-danger-ink focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-destructive"
              onClick={() => {
                setProblem(undefined);
                setDialog("delete");
              }}
            >
              {t("customers.delete")}
            </button>
          )}
        </aside>
      </div>
      {dialog === "edit" && (
        <Suspense fallback={null}>
          <CustomerDialog
            customer={customer}
            onSaved={() => setDialog(undefined)}
            onClose={() => setDialog(undefined)}
          />
        </Suspense>
      )}
      {dialog === "delete" && (
        <ConfirmDialog
          title={t("customers.deleteTitle", { name: customer.name })}
          description={t("customers.deleteDescription")}
          problem={problem}
          confirm={t("customers.delete")}
          pending={remove.isPending}
          onConfirm={() => remove.mutate()}
          onClose={() => setDialog(undefined)}
        />
      )}
    </div>
  );
}

function OrdersSection({ phone }: { phone: string }) {
  const { t } = useTranslation();
  const format = useFormat();
  const [paging, setPaging] = useState<Paging>({});
  const top = useRef<HTMLDivElement>(null);
  const orders = useQuery(customerOrdersQuery(phone, paging));
  const items = orders.data?.items ?? [];
  return (
    <div ref={top} className="flex min-w-0 scroll-mt-16 flex-col gap-3.5 md:scroll-mt-4">
      <section className="overflow-hidden rounded-[14px] border bg-card">
        <h2 className="px-5 py-3.25 text-[19px] font-bold max-md:px-4">{t("customers.orders")}</h2>
        {orders.isSuccess && items.length === 0 && (
          <div className="flex flex-col items-start gap-2 border-t px-5 pt-3.5 pb-4.5 max-md:px-4">
            <p>{t("customers.noOrders")}</p>
            <Button asChild variant="outline" className="h-11 rounded-[10px] border-[1.5px] font-semibold">
              <Link to="/orders/new" search={{ phone }} className="text-foreground no-underline">
                <Plus aria-hidden="true" strokeWidth={2.5} />
                {t("orders.new")}
              </Link>
            </Button>
          </div>
        )}
        {items.length > 0 && (
          <ul>
            {items.map((order) => (
              <li key={order.id} className="border-t">
                <Link
                  to="/orders/$orderId"
                  params={{ orderId: order.id }}
                  className="flex items-center justify-between gap-3 px-5 py-2.5 text-foreground no-underline hover:bg-background max-md:px-4"
                >
                  <span className="min-w-0">
                    <span className="block font-bold text-primary">{format.orderNumber(order.numberLabel)}</span>
                    <span className="block text-sm text-muted-foreground">
                      {t("customers.orderLine", {
                        count: format.number(order.itemCount),
                        date: format.date(order.createdAt),
                      })}
                    </span>
                  </span>
                  <span className="flex flex-none flex-col items-end gap-0.75">
                    <span className="font-bold">{format.taka(order.total)}</span>
                    <StatusBadge status={order.status} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      <Pagination result={orders.data} onChange={setPaging} scrollTo={top} />
    </div>
  );
}
