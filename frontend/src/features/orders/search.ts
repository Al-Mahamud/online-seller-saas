import type { components } from "@/api/schema";
import { searchText } from "@/lib/search";

export type OrderStatus = components["schemas"]["OrderDetail"]["status"];

export const ORDER_STATUSES: OrderStatus[] = [
  "DRAFT",
  "CONFIRMED",
  "PACKED",
  "SHIPPED",
  "DELIVERED",
  "RETURNED",
  "CANCELLED",
];

/** What the orders list keeps in the address: a shared link shows the same list. */
export interface OrderFilters {
  status?: OrderStatus;
  q?: string;
  from?: string;
  to?: string;
}

const dateText = (value: unknown) =>
  typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : undefined;

export function orderFilters(search: Record<string, unknown>): OrderFilters {
  return {
    status: ORDER_STATUSES.find((status) => status === search.status),
    q: searchText(search.q),
    from: dateText(search.from),
    to: dateText(search.to),
  };
}
