import { queryOptions } from "@tanstack/react-query";
import type { LinkProps } from "@tanstack/react-router";
import { api, call } from "@/api/client";
import type { components } from "@/api/schema";
import { type Paging, pagedQuery } from "@/lib/paging";

export type Notification = components["schemas"]["NotificationItem"];

const EVERY_MINUTE = 60_000;

/** How many are unread, asked every minute while the app is on screen. */
export const unreadQuery = queryOptions({
  queryKey: ["notifications", "unread"],
  queryFn: async () => (await call(api.GET("/api/v1/notifications", { params: { query: { size: 1 } } }))).unread,
  refetchInterval: EVERY_MINUTE,
  meta: { showsOwnErrors: true },
});

export const notificationsQuery = (paging: Paging) =>
  pagedQuery(["notifications", "list", paging], () =>
    call(api.GET("/api/v1/notifications", { params: { query: paging } })),
  );

export const preferencesQuery = queryOptions({
  queryKey: ["notification-preferences"],
  queryFn: () => call(api.GET("/api/v1/notifications/preferences")),
});

/** Where a notice leads: the thing it is about. Types the app doesn't know yet lead nowhere. */
export function notificationLink(notification: Pick<Notification, "type" | "payload">): LinkProps | null {
  const payload = notification.payload as Record<string, unknown>;
  const id = (key: string) => (typeof payload[key] === "string" ? (payload[key] as string) : null);
  const productId = id("productId");
  const importId = id("importId");
  const invoiceId = id("invoiceId");
  if (notification.type === "stock.low" && productId) {
    return { to: "/products/$productId", params: { productId } };
  }
  if (notification.type === "import.finished" && importId) {
    return { to: "/products/import", search: { job: importId } };
  }
  if (notification.type === "billing.paid" && invoiceId) {
    return { to: "/billing/invoices/$invoiceId", params: { invoiceId } };
  }
  return notification.type.startsWith("billing.") ? { to: "/billing" } : null;
}
