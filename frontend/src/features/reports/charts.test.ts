import { describe, expect, it } from "vitest";
import { buckets, changeFrom, daysBetween, OTHER_COLOR, rankedSlices, SLICE_COLORS, slicePath, slices } from "./charts";

const part = (key: string, value: number) => ({ key, name: key, value });

describe("pie slices", () => {
  it("shares out the whole, and leaves out what has no value", () => {
    const shown = slices([part("a", 300), part("b", 100), part("none", 0), part("back", -50)]);
    expect(shown.map((slice) => slice.key)).toEqual(["a", "b"]);
    expect(shown.map((slice) => slice.share)).toEqual([0.75, 0.25]);
  });

  it("puts the largest first, whatever order they came in", () => {
    expect(rankedSlices([part("small", 1), part("big", 9)], "other").map((slice) => slice.key)).toEqual([
      "big",
      "small",
    ]);
  });

  it("keeps up to six apart, then folds the smallest into one grey slice", () => {
    const six = ["a", "b", "c", "d", "e", "f"].map((key, index) => part(key, 60 - index));
    expect(rankedSlices(six, "other")).toHaveLength(6);

    const shown = rankedSlices([...six, part("g", 10)], "other");
    expect(shown).toHaveLength(6);
    expect(shown.slice(0, 5).map((slice) => slice.color)).toEqual([...SLICE_COLORS]);
    const last = shown[5];
    expect(last?.name).toBe("other");
    expect(last?.value).toBe(55 + 10);
    expect(last?.color).toBe(OTHER_COLOR);
  });

  it("keeps a colour it was given", () => {
    expect(slices([{ ...part("cost", 1), color: "#123456" }])[0]?.color).toBe("#123456");
  });

  it("draws a whole circle, since an arc that ends where it starts draws nothing", () => {
    expect(slicePath(0, 1)).toBe("M100 4A96 96 0 1 1 100 196A96 96 0 1 1 100 4Z");
    expect(slicePath(0, 0.25)).toMatch(/^M100 100L100\.00 4\.00A96 96 0 0 1 196\.00 100\.00Z$/);
    expect(slicePath(0, 0.75)).toContain("A96 96 0 1 1");
  });
});

describe("day columns", () => {
  const days = (count: number) =>
    Array.from({ length: count }, (_, index) => ({
      key: new Date(Date.UTC(2026, 0, 1 + index)).toISOString().slice(0, 10),
      value: 100,
      orders: 1,
    }));

  it("has a column a day up to 45 days", () => {
    expect(buckets(days(45))).toHaveLength(45);
  });

  it("adds up by the week, then by about the month, losing nothing", () => {
    const weeks = buckets(days(46));
    expect(weeks).toHaveLength(7);
    expect(weeks[0]).toEqual({ first: "2026-01-01", last: "2026-01-07", value: 700, orders: 7 });
    expect(weeks.reduce((sum, column) => sum + column.value, 0)).toBe(4600);

    const months = buckets(days(366));
    expect(months).toHaveLength(13);
    expect(months.reduce((sum, column) => sum + column.orders, 0)).toBe(366);
  });
});

describe("comparing periods", () => {
  it("counts both ends", () => {
    expect(daysBetween("2026-10-04", "2026-11-02")).toBe(30);
    expect(daysBetween("2026-11-02", "2026-11-02")).toBe(1);
  });

  it("says nothing when there was nothing before", () => {
    expect(changeFrom(0, 500)).toBeNull();
    expect(changeFrom(1000, 1120)).toBe(12);
    expect(changeFrom(1000, 900)).toBe(-10);
  });
});
