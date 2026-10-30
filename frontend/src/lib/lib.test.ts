import { describe, expect, it } from "vitest";
import { latinDigits } from "./digits";
import { safeRedirect } from "./redirect";

describe("latinDigits", () => {
  it("turns Bangla digits into Latin ones and leaves the rest", () => {
    expect(latinDigits("০১৭১২-৩৪৫৬৭৮")).toBe("01712-345678");
    expect(latinDigits("seller9@example.com")).toBe("seller9@example.com");
  });
});

describe("safeRedirect", () => {
  it("keeps paths on this site", () => {
    expect(safeRedirect("/orders?status=CONFIRMED")).toBe("/orders?status=CONFIRMED");
    expect(safeRedirect("/")).toBe("/");
  });

  it("drops anything that could leave the site", () => {
    for (const target of ["https://evil.example", "//evil.example", "/\\evil.example", "evil", "", 5, undefined]) {
      expect(safeRedirect(target)).toBeUndefined();
    }
  });
});
