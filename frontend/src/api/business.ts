import { type QueryClient, queryOptions, useQuery } from "@tanstack/react-query";
import { redirect } from "@tanstack/react-router";
import { useRef } from "react";
import { api, call } from "./client";
import { ApiError } from "./errors";
import type { components } from "./schema";

export type Business = components["schemas"]["BusinessResponse"];
export type Role = Business["role"];

const RANK: Record<Role, number> = { STAFF: 1, MANAGER: 2, OWNER: 3 };

/** The UI hides what a role cannot do; the server still decides. */
export function hasRole(business: { role: Role }, needed: Role): boolean {
  return RANK[business.role] >= RANK[needed];
}

/** On a page the role doesn't reach, such as from an old link: back to the list of pages. */
export function requireRole(business: { role: Role }, needed: Role) {
  if (!hasRole(business, needed)) {
    throw redirect({ to: "/more" });
  }
}

let onChangedUnderneath: (lost: Business) => void = () => {};

/** What to do when the server's selected business stops being the one on screen (see businessQuery). */
export function whenBusinessChangesUnderneath(handler: (lost: Business) => void) {
  onChangedUnderneath = handler;
}

/**
 * The selected business, or null with none. It comes from the session, and the server moves it when a
 * member is removed: the next request silently runs in their other business, or in none. So this is
 * read again every minute and on returning to the app, and a different answer leaves the screen at
 * once, before anything is done to the wrong business.
 */
export const businessQuery = queryOptions({
  queryKey: ["business"],
  queryFn: async ({ client }): Promise<Business | null> => {
    const before = client.getQueryData<Business | null>(["business"]);
    let now: Business | null;
    try {
      now = await call(api.GET("/api/v1/business"));
    } catch (error) {
      if (!(error instanceof ApiError && error.code === "business.not_selected")) {
        throw error;
      }
      now = null;
    }
    if (before && before.id !== now?.id) {
      onChangedUnderneath(before);
    }
    return now;
  },
  staleTime: 30_000,
  refetchInterval: 60_000,
  meta: { showsOwnErrors: true },
});

/** The people's businesses, for the switcher. */
export const businessesQuery = queryOptions({
  queryKey: ["businesses"],
  queryFn: () => call(api.GET("/api/v1/businesses")),
});

export const settingsQuery = queryOptions({
  queryKey: ["business-settings"],
  queryFn: () => call(api.GET("/api/v1/business/settings")),
});

/** The selected business, on screens behind the guard, kept current as it changes. */
export function useBusiness(): Business {
  const { data } = useQuery(businessQuery);
  const last = useRef(data);
  if (data) {
    last.current = data;
  }
  return last.current as Business;
}

/** Nothing of one business stays cached when another is selected. */
export function resetBusinessData(queryClient: QueryClient) {
  queryClient.removeQueries({
    predicate: (query) => query.queryKey[0] !== "me" && query.queryKey[0] !== "auth-options",
  });
}

/** Another business was selected on the server: `business` is the server's answer. */
export function businessSelected(queryClient: QueryClient, business: Business) {
  // Set first and left in the cache, so the screens showing it update at once.
  queryClient.setQueryData(businessQuery.queryKey, business);
  queryClient.removeQueries({
    predicate: (query) => !["me", "auth-options", "business"].includes(query.queryKey[0] as string),
  });
}

export function initials(name: string): string {
  const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });
  const firstLetters = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => segmenter.segment(word)[Symbol.iterator]().next().value?.segment ?? "");
  return firstLetters.join("").toUpperCase();
}
