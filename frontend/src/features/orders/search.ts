import type { components } from "@/api/schema";
import { dayText } from "@/lib/dates";
import { type Paging, pagingSearch } from "@/lib/paging";
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
export interface OrderFilters extends Paging {
  status?: OrderStatus;
  q?: string;
  from?: string;
  to?: string;
}

export function orderFilters(search: Record<string, unknown>): OrderFilters {
  return {
    status: ORDER_STATUSES.find((status) => status === search.status),
    q: searchText(search.q),
    from: dayText(search.from),
    to: dayText(search.to),
    ...pagingSearch(search),
  };
}

/** What the COD page keeps in the address: a shared link shows the same courier and search. */
export interface CodFilters extends Paging {
  courier?: string;
  q?: string;
  from?: string;
  to?: string;
}

export function codFilters(search: Record<string, unknown>): CodFilters {
  return {
    courier: searchText(search.courier),
    q: searchText(search.q),
    from: dayText(search.from),
    to: dayText(search.to),
    ...pagingSearch(search),
  };
}
