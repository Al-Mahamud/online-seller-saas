import { queryOptions, useQuery } from "@tanstack/react-query";
import { api, call } from "@/api/client";

/** Read again every minute, so a trial that ends or a payment that arrives shows without a reload. */
export const subscriptionStatusQuery = queryOptions({
  queryKey: ["subscription-status"],
  queryFn: () => call(api.GET("/api/v1/billing/status")),
  refetchInterval: 60_000,
  meta: { showsOwnErrors: true },
});

/** Whether the business can change anything now; the server decides, and this spares a long form that it would refuse. */
export function useCanWrite(): boolean {
  const { data } = useQuery(subscriptionStatusQuery);
  return data?.status !== "READ_ONLY" && data?.status !== "SUSPENDED";
}
