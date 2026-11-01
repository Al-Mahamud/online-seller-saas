import { keepPreviousData, type QueryKey, queryOptions } from "@tanstack/react-query";

/** The sizes a person can choose from. The server takes any size up to its own limit. */
export const PAGE_SIZES = [20, 50, 100] as const;
export const DEFAULT_PAGE_SIZE = 20;

/** Which page of a list is asked for. Left out, as in a clean address: the first page, at the default size. */
export interface Paging {
  page?: number;
  size?: number;
}

/** What every paged answer from the server says about itself. */
export interface PageInfo {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

function positiveWhole(value: unknown): number | undefined {
  const number = typeof value === "string" ? Number(value) : value;
  return typeof number === "number" && Number.isInteger(number) && number >= 1 ? number : undefined;
}

/** Paging from the address, for a route's `validateSearch`. Anything else in it means the defaults. */
export function pagingSearch(search: Record<string, unknown>): Paging {
  const page = positiveWhole(search.page);
  const size = positiveWhole(search.size);
  return {
    page: page === 1 ? undefined : page,
    size: size !== DEFAULT_PAGE_SIZE && PAGE_SIZES.some((choice) => choice === size) ? size : undefined,
  };
}

/** The same shape as {@link pagingSearch}, for what a pager asks to go to. */
export function pagingOf(page: number, size: number): Paging {
  return pagingSearch({ page, size });
}

/**
 * One page of a list. The page on screen stays until the next one arrives, so changing page doesn't
 * blank the list and shift everything under the pointer.
 */
export function pagedQuery<T>(queryKey: QueryKey, queryFn: () => Promise<T>) {
  return queryOptions({ queryKey, queryFn, placeholderData: keepPreviousData });
}

/** For a list read a page at a time as it scrolls. */
export function nextPage({ page, totalPages }: PageInfo): number | undefined {
  return page < totalPages ? page + 1 : undefined;
}

/** How many numbers either side of the current page are always shown. */
const AROUND = 1;
/** Up to this many pages, every number fits and none is left out. */
const ALL_FIT = 7;

/**
 * The page numbers to show: the first, the last, and those around the current one, with a gap where
 * some are left out. A gap never hides just one page, which could as well be shown in its place.
 */
export function pageNumbers(current: number, last: number): (number | "gap")[] {
  if (last <= ALL_FIT) {
    return Array.from({ length: last }, (_, index) => index + 1);
  }
  const wanted = new Set([1, last]);
  for (let page = current - AROUND; page <= current + AROUND; page++) {
    if (page > 1 && page < last) {
      wanted.add(page);
    }
  }
  const sorted = [...wanted].sort((a, b) => a - b);
  return sorted.flatMap((page, index) => {
    const before = sorted[index - 1];
    if (before === undefined || page - before === 1) {
      return [page];
    }
    return page - before === 2 ? [page - 1, page] : (["gap", page] as const);
  });
}
