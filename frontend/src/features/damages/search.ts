import { dayText } from "@/lib/dates";
import { type Paging, pagingSearch } from "@/lib/paging";
import { searchText } from "@/lib/search";
import type { DamageSource } from "./api";

/** What the damaged goods page keeps in the address: a shared link shows the same list. */
export interface DamageFilters extends Paging {
  from?: string;
  to?: string;
  q?: string;
  source?: DamageSource;
}

export function damageFilters(search: Record<string, unknown>): DamageFilters {
  return {
    from: dayText(search.from),
    to: dayText(search.to),
    q: searchText(search.q),
    source: search.source === "SHELF" || search.source === "RETURN" ? search.source : undefined,
    ...pagingSearch(search),
  };
}
