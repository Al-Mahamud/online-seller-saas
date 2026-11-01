import { describe, expect, it } from "vitest";
import { nextPage, pageNumbers, pagingOf, pagingSearch } from "./paging";

describe("pageNumbers", () => {
  it("shows every page when they all fit", () => {
    expect(pageNumbers(1, 1)).toEqual([1]);
    expect(pageNumbers(3, 7)).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it("keeps the first, the last and those around the current page", () => {
    expect(pageNumbers(15, 31)).toEqual([1, "gap", 14, 15, 16, "gap", 31]);
  });

  it("leaves no gap at the ends of the list", () => {
    expect(pageNumbers(1, 20)).toEqual([1, 2, "gap", 20]);
    expect(pageNumbers(20, 20)).toEqual([1, "gap", 19, 20]);
  });

  it("shows a page rather than a gap that would hide only that page", () => {
    expect(pageNumbers(4, 20)).toEqual([1, 2, 3, 4, 5, "gap", 20]);
    expect(pageNumbers(17, 20)).toEqual([1, "gap", 16, 17, 18, 19, 20]);
  });

  it("copes with a page past the end", () => {
    expect(pageNumbers(50, 20)).toEqual([1, "gap", 20]);
  });
});

describe("pagingSearch", () => {
  it("keeps a sensible page and size", () => {
    expect(pagingSearch({ page: 3, size: 50 })).toEqual({ page: 3, size: 50 });
    expect(pagingSearch({ page: "3", size: "100" })).toEqual({ page: 3, size: 100 });
  });

  it("leaves the defaults out, for a clean address", () => {
    expect(pagingSearch({ page: 1, size: 20 })).toEqual({ page: undefined, size: undefined });
    expect(pagingOf(1, 20)).toEqual({ page: undefined, size: undefined });
  });

  it("ignores anything else in the address", () => {
    expect(pagingSearch({})).toEqual({ page: undefined, size: undefined });
    expect(pagingSearch({ page: "first", size: 7 })).toEqual({ page: undefined, size: undefined });
    expect(pagingSearch({ page: -2, size: 1.5 })).toEqual({ page: undefined, size: undefined });
  });
});

describe("nextPage", () => {
  const info = { size: 20, totalItems: 41, totalPages: 3 };

  it("goes on until the last page", () => {
    expect(nextPage({ ...info, page: 2 })).toBe(3);
    expect(nextPage({ ...info, page: 3 })).toBeUndefined();
  });
});
