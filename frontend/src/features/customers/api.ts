import { queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { latinDigits } from "@/lib/digits";
import { type Paging, pagedQuery } from "@/lib/paging";

export type Customer = components["schemas"]["CustomerView"];

/**
 * A whole phone number, written any way (01712-345678, +880 1712 345678, ০১৭১২৩৪৫৬৭৮), goes to the
 * server as a phone number, which finds the one customer with it. Anything else searches names and
 * parts of numbers.
 */
export function customerSearch(text: string): { phone?: string; q?: string } {
  const typed = latinDigits(text).trim();
  if (!typed) {
    return {};
  }
  const looksLikeNumber = /^[+\d\s()-]+$/.test(typed);
  const digits = typed.replace(/\D/g, "");
  return looksLikeNumber && digits.length >= 11 ? { phone: typed } : { q: looksLikeNumber ? digits : typed };
}

export const customersQuery = (search: string, paging: Paging) =>
  pagedQuery(["customers", search, paging], () =>
    call(api.GET("/api/v1/customers", { params: { query: { ...customerSearch(search), ...paging } } })),
  );

export const customerQuery = (id: string) =>
  queryOptions({
    queryKey: ["customer", id],
    queryFn: () => call(api.GET("/api/v1/customers/{id}", { params: { path: { id } } })),
  });

/** A customer's orders: the server finds them by the phone number on the order. */
export const customerOrdersQuery = (phone: string, paging: Paging) =>
  pagedQuery(["customer-orders", phone, paging], () =>
    call(api.GET("/api/v1/orders", { params: { query: { phone, ...paging } } })),
  );
