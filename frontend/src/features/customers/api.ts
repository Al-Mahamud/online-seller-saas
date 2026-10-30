import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { latinDigits } from "@/lib/digits";

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

export const customersQuery = (search: string) =>
  infiniteQueryOptions({
    queryKey: ["customers", search],
    queryFn: ({ pageParam }) =>
      call(api.GET("/api/v1/customers", { params: { query: { ...customerSearch(search), cursor: pageParam } } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });

export const customerQuery = (id: string) =>
  queryOptions({
    queryKey: ["customer", id],
    queryFn: () => call(api.GET("/api/v1/customers/{id}", { params: { path: { id } } })),
  });

/** A customer's orders: the server finds them by the phone number on the order. */
export const customerOrdersQuery = (phone: string) =>
  infiniteQueryOptions({
    queryKey: ["customer-orders", phone],
    queryFn: ({ pageParam }) => call(api.GET("/api/v1/orders", { params: { query: { phone, cursor: pageParam } } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
