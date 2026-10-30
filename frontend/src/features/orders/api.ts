import { infiniteQueryOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import { ApiError } from "@/api/errors";
import { errorMessages } from "@/api/query";
import type { components } from "@/api/schema";
import i18n from "@/i18n";
import { latinDigits } from "@/lib/digits";
import { formatNumber } from "@/lib/format";
import type { OrderFilters } from "./search";

export type Order = components["schemas"]["OrderDetail"];
export type OrderSummary = components["schemas"]["OrderSummary"];
export type OrderItem = components["schemas"]["OrderItem"];
export type PaymentMethod = components["schemas"]["OrderPaymentRequest"]["method"];
export type BulkResult = components["schemas"]["BulkActionsResult"];
export type CodRow = components["schemas"]["CodSettlementsRow"];
export type Disposition = components["schemas"]["OrderReturnLineRequest"]["disposition"];

export const PAYMENT_METHODS: PaymentMethod[] = ["BKASH", "NAGAD", "ROCKET", "BANK", "CASH", "CARD", "OTHER"];

/** A whole phone number finds orders by phone; anything else with digits in it is an order number. */
export function orderSearch(text: string | undefined): { phone?: string; number?: string } {
  const typed = latinDigits(text ?? "").trim();
  if (!typed) {
    return {};
  }
  return typed.replace(/\D/g, "").length >= 11 ? { phone: typed } : { number: typed };
}

const listQuery = ({ q, from, to }: OrderFilters) => ({ ...orderSearch(q), from, to });

export const ordersQuery = (filters: OrderFilters) =>
  infiniteQueryOptions({
    queryKey: ["orders", filters],
    queryFn: ({ pageParam }) =>
      call(
        api.GET("/api/v1/orders", {
          params: {
            query: {
              ...listQuery(filters),
              status: filters.status ? [filters.status] : undefined,
              cursor: pageParam,
            },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });

export const orderCountsQuery = (filters: OrderFilters) =>
  queryOptions({
    queryKey: ["order-counts", filters.q, filters.from, filters.to],
    queryFn: () => call(api.GET("/api/v1/orders/counts", { params: { query: listQuery(filters) } })),
  });

export const orderQuery = (id: string) =>
  queryOptions({
    queryKey: ["order", id],
    queryFn: () => call(api.GET("/api/v1/orders/{id}", { params: { path: { id } } })),
  });

export const couriersQuery = queryOptions({
  queryKey: ["couriers"],
  queryFn: () => call(api.GET("/api/v1/shipments/couriers")),
});

export const codOwedQuery = queryOptions({
  queryKey: ["shipments", "owed"],
  queryFn: () =>
    call(api.GET("/api/v1/shipments", { params: { query: { status: "DELIVERED", codStatus: "WITH_COURIER" } } })),
});

export const codInTransitQuery = queryOptions({
  queryKey: ["shipments", "in-transit"],
  queryFn: () =>
    call(api.GET("/api/v1/shipments", { params: { query: { status: "IN_TRANSIT", codStatus: "WITH_COURIER" } } })),
});

/** An order moves stock, money and the lists: whatever shows those reads again. */
export function ordersChanged(queryClient: QueryClient) {
  for (const key of [
    "orders",
    "order-counts",
    "customer-orders",
    "customers",
    "customer",
    "shipments",
    "couriers",
    "products",
    "product",
    "low-stock",
    "movements",
  ]) {
    void queryClient.invalidateQueries({ queryKey: [key] });
  }
}

/** The answer to a change to one order: its page has it at once. */
export function orderChanged(queryClient: QueryClient, order: Order) {
  queryClient.setQueryData(orderQuery(order.id).queryKey, order);
  ordersChanged(queryClient);
}

interface Shortage {
  productName: string;
  variantLabel: string;
  available: number;
}

function language() {
  return i18n.resolvedLanguage === "en" ? "en" : "bn";
}

/** Why the server refused: which items are short, or what's wrong with which amounts. */
export function orderErrorText(error: unknown): string {
  if (error instanceof ApiError && error.code === "stock.insufficient" && Array.isArray(error.properties.shortages)) {
    return (error.properties.shortages as Shortage[])
      .map((short) =>
        i18n.t("orders.short", {
          name: [short.productName, short.variantLabel].filter(Boolean).join(" "),
          count: formatNumber(short.available, language()),
        }),
      )
      .join(" ");
  }
  return errorMessages(error);
}

/** How much of each line can still come back from the customer. */
export function stillOut(order: Order): Map<string, number> {
  const out = new Map(order.items.map((item) => [item.id, item.quantity]));
  for (const back of order.returns) {
    if (back.kind !== "FAILED_DELIVERY") {
      for (const item of back.items) {
        out.set(item.orderItemId, (out.get(item.orderItemId) ?? 0) - item.quantity);
      }
    }
  }
  return out;
}

/** The page of slips to print, in the language on screen. */
export function slipsUrl(ids: string[]): string {
  const language = i18n.resolvedLanguage === "en" ? "en" : "bn";
  return ids.length === 1
    ? `/api/v1/orders/${ids[0]}/slip?lang=${language}&autoprint=true`
    : `/api/v1/orders/print?ids=${ids.join(",")}&lang=${language}&autoprint=true`;
}
