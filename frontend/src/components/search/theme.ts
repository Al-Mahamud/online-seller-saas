import { useQuery } from "@tanstack/react-query";
import { settingsQuery } from "@/api/business";
import type { components } from "@/api/schema";

export type SearchTheme = components["schemas"]["SettingsResponse"]["searchTheme"];
export const SEARCH_THEMES: SearchTheme[] = ["LEDGER", "MINT"];

/** The owner's choice for the whole business; the ledger look until it's known. */
export function useSearchTheme(): SearchTheme {
  const { data } = useQuery({ ...settingsQuery, staleTime: 5 * 60_000 });
  return data?.searchTheme ?? "LEDGER";
}

/**
 * The classes of each look. `band` is the panel standing alone from 768 px; on phones, and inside a
 * dark header (`inset`), the ledger look has no ground of its own and the mint one is a card.
 */
export const SEARCH_LOOK = {
  LEDGER: {
    band: "md:relative md:rounded-2xl md:bg-ledger md:py-4.5 md:pr-6 md:pl-14 md:text-white",
    card: "",
    phoneCard: "",
    line: "bg-marigold",
    focus: "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-marigold-text",
    ring: "shadow-[0_12px_30px_rgba(0,0,0,0.28)] has-[input:focus-visible]:ring-3 has-[input:focus-visible]:ring-marigold-text",
    button: "bg-marigold-text text-ledger hover:bg-marigold",
    chip: "border-white/25 bg-white/10 text-white hover:bg-white/15",
    chipKind: "text-ledger-text",
    reset: "text-marigold-text hover:bg-white/10",
    sub: "text-ledger-text",
    strong: "text-white",
    filter: "border-ledger-text text-white hover:bg-white/10",
    badge: "border-ledger bg-marigold-text text-ledger",
    pillOn: "border-primary bg-accent text-accent-foreground",
  },
  MINT: {
    band: "md:relative md:rounded-2xl md:border md:border-mint-line md:bg-accent md:py-4.5 md:pr-6 md:pl-14 md:text-accent-foreground",
    card: "rounded-2xl bg-accent p-3 text-accent-foreground",
    phoneCard: "max-md:rounded-2xl max-md:bg-accent max-md:p-3 max-md:text-accent-foreground",
    line: "bg-primary",
    focus: "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ledger",
    ring: "shadow-[0_8px_22px_rgba(11,107,79,0.16)] has-[input:focus-visible]:ring-3 has-[input:focus-visible]:ring-primary",
    button: "bg-ledger text-white hover:bg-[#0b4d3a]",
    chip: "border-mint-chip bg-card text-accent-foreground hover:bg-[#f3f9f6]",
    chipKind: "text-mint-sub",
    reset: "text-ledger hover:bg-white/70",
    sub: "text-mint-sub",
    strong: "text-accent-foreground",
    filter: "border-mint-chip bg-card text-ledger hover:bg-[#f3f9f6]",
    badge: "border-accent bg-ledger text-white",
    pillOn: "border-ledger bg-ledger text-white",
  },
} as const satisfies Record<SearchTheme, Record<string, string>>;
