import { type QueryClient, queryOptions, useQuery } from "@tanstack/react-query";
import { useRef } from "react";
import { type Language, setLanguage } from "@/i18n";
import { api, call } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema";

export type Me = components["schemas"]["MeResponse"];

/** The signed-in account, or null when nobody is signed in. Route guards read it from the cache. */
export const meQuery = queryOptions({
  queryKey: ["me"],
  queryFn: async (): Promise<Me | null> => {
    try {
      return await call(api.GET("/api/v1/me"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return null;
      }
      throw error;
    }
  },
  staleTime: 5 * 60_000,
  // The route's error page says so when this fails.
  meta: { showsOwnErrors: true },
});

/**
 * The account, on screens behind sign-in, kept current as it changes. The last one is kept for the
 * moment between signing out and the page changing.
 */
export function useMe(): Me {
  const { data } = useQuery(meQuery);
  const last = useRef(data);
  if (data) {
    last.current = data;
  }
  return last.current as Me;
}

/** Which ways of receiving a code the server has: no SMS until a gateway is set up. */
export const authOptionsQuery = queryOptions({
  queryKey: ["auth-options"],
  queryFn: () => call(api.GET("/api/v1/auth/options")),
  staleTime: 5 * 60_000,
});

export function accountLanguage(me: Me): Language {
  return me.locale === "en" ? "en" : "bn";
}

export async function signedIn(queryClient: QueryClient, me: Me) {
  queryClient.setQueryData(meQuery.queryKey, me);
  await setLanguage(accountLanguage(me));
}

/** Nothing of the last account stays in memory. */
export function signedOut(queryClient: QueryClient) {
  queryClient.clear();
  queryClient.setQueryData(meQuery.queryKey, null);
}
