import { describe, expect, it } from "vitest";
import { matchesPlace } from "@/features/delivery/geo";
import { customerSearch } from "./api";

describe("customerSearch", () => {
  it("sends a whole number as a phone number, however it's written", () => {
    for (const written of ["01712345678", "01712-345678", "+880 1712 345678", "8801712345678", "০১৭১২৩৪৫৬৭৮"]) {
      expect(customerSearch(written).phone).toBeDefined();
    }
    expect(customerSearch("০১৭১২৩৪৫৬৭৮")).toEqual({ phone: "01712345678" });
  });

  it("searches for part of a number by its digits", () => {
    expect(customerSearch("1712-3")).toEqual({ q: "17123" });
  });

  it("searches names otherwise", () => {
    expect(customerSearch(" রহিমা ")).toEqual({ q: "রহিমা" });
    expect(customerSearch("")).toEqual({});
  });
});

describe("matchesPlace", () => {
  const dhaka = { id: 1, nameBn: "ঢাকা", nameEn: "Dhaka" };

  it("finds a place by either language, any case", () => {
    expect(matchesPlace(dhaka, "ঢাকা")).toBe(true);
    expect(matchesPlace(dhaka, "dha")).toBe(true);
    expect(matchesPlace(dhaka, "খুলনা")).toBe(false);
    expect(matchesPlace(dhaka, "  ")).toBe(true);
  });
});
