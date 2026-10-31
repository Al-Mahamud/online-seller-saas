import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { ChevronLeft } from "lucide-react";
import { lazy, Suspense, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { hasRole, useBusiness } from "@/api/business";
import { api, call } from "@/api/client";
import { ConfirmDialog } from "@/components/form/ConfirmDialog";
import { LoadMore } from "@/components/layout/LoadMore";
import { Section } from "@/components/layout/Section";
import { Button } from "@/components/ui/button";
import { usePlaceName } from "@/features/delivery/geo";
import { useFormat } from "@/lib/format";
import { customerOrdersQuery, customerQuery } from "./api";

// With the form libraries it brings, this is only needed when the dialog opens.
const CustomerDialog = lazy(() => import("./CustomerDialog"));

const route = getRouteApi("/_app/customers/$customerId");

function BackToCustomers() {
  const { t } = useTranslation();
  return (
    <Link to="/customers" className="-ml-1 flex min-h-11 w-fit items-center gap-1 text-primary no-underline">
      <ChevronLeft aria-hidden="true" className="size-5" />
      {t("customers.title")}
    </Link>
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
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/customers/{id}", { params: { path: { id: customerId } } })),
    onSuccess: async () => {
      toast.success(t("customers.deleted"));
      void queryClient.invalidateQueries({ queryKey: ["customers"] });
      await navigate({ to: "/customers" });
    },
    // Refused while they have orders in progress, which the server's message says.
    onSettled: () => setDialog(undefined),
  });
  if (!customer) {
    return <BackToCustomers />;
  }
  const place = [customer.area, customer.district].flatMap((part) => (part ? [placeName(part)] : [])).join(", ");
  return (
    <div className="flex max-w-2xl flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <BackToCustomers />
          <h1 className="text-2xl font-bold tracking-tight break-words md:text-3xl">{customer.name}</h1>
          <p className="text-muted-foreground">
            {t("customers.orderCount", { count: format.number(customer.orderCount) })}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button asChild>
            <Link to="/orders/new" search={{ phone: customer.phone }}>
              {t("orders.new")}
            </Link>
          </Button>
          <Button variant="outline" onClick={() => setDialog("edit")}>
            {t("customers.edit")}
          </Button>
          {hasRole(business, "MANAGER") && (
            <Button variant="outline" onClick={() => setDialog("delete")}>
              {t("customers.delete")}
            </Button>
          )}
        </div>
      </div>
      <Section title={t("customers.details")}>
        <div className="flex flex-col gap-1">
          <a href={`tel:${customer.phone}`} className="w-fit font-medium text-primary">
            {format.phone(customer.phone)}
          </a>
          {customer.altPhone && (
            <a href={`tel:${customer.altPhone}`} className="w-fit text-primary">
              {format.phone(customer.altPhone)}
            </a>
          )}
          {customer.address && <p className="whitespace-pre-wrap">{customer.address}</p>}
          {place && <p className="text-muted-foreground">{place}</p>}
          {customer.note && <p className="mt-2 rounded-lg bg-muted p-2 text-sm whitespace-pre-wrap">{customer.note}</p>}
        </div>
      </Section>
      <OrdersSection phone={customer.phone} />
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
  const orders = useInfiniteQuery(customerOrdersQuery(phone));
  const items = orders.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <Section title={t("customers.orders")}>
      {orders.isSuccess && items.length === 0 && <p className="text-muted-foreground">{t("customers.noOrders")}</p>}
      {items.length > 0 && (
        <ul className="flex flex-col">
          {items.map((order) => (
            <li key={order.id} className="border-t first:border-t-0">
              <Link
                to="/orders/$orderId"
                params={{ orderId: order.id }}
                className="flex items-center justify-between gap-3 py-2 text-foreground no-underline"
              >
                <span className="min-w-0">
                  <span className="block font-medium">{format.orderNumber(order.numberLabel)}</span>
                  <span className="block text-sm text-muted-foreground">
                    {t("customers.orderLine", {
                      count: format.number(order.itemCount),
                      date: format.date(order.createdAt),
                    })}
                  </span>
                </span>
                <span className="flex-none text-right">
                  <span className="block font-semibold">{format.taka(order.total)}</span>
                  <span className="block text-sm text-muted-foreground">{t(`orderStatus.${order.status}`)}</span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <LoadMore query={orders} />
    </Section>
  );
}
