import { describe, expect, it } from "vitest";
import { addDays, dayText, dhakaToday } from "./dates";
import { latinDigits } from "./digits";
import { parseCount, parseTaka, takaInput } from "./money";
import { safeRedirect } from "./redirect";
import { searchText } from "./search";

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

describe("parseTaka", () => {
  it("reads whole taka and poisha in either digits", () => {
    expect(parseTaka("1500")).toBe(150000);
    expect(parseTaka("১,৫০০")).toBe(150000);
    expect(parseTaka("1200.5")).toBe(120050);
    expect(parseTaka("৳ 19.99")).toBe(1999);
    expect(parseTaka("0")).toBe(0);
  });

  it("refuses what isn't an amount", () => {
    for (const text of ["", "abc", "-5", "1.234", "1.2.3"]) {
      expect(parseTaka(text)).toBeNull();
    }
  });
});

describe("takaInput", () => {
  it("shows poisha only when there are some, and reads back the same", () => {
    expect(takaInput(150000)).toBe("1500");
    expect(takaInput(120050)).toBe("1200.50");
    expect(parseTaka(takaInput(120050))).toBe(120050);
  });
});

describe("parseCount", () => {
  it("reads whole numbers in either digits", () => {
    expect(parseCount("১২")).toBe(12);
    expect(parseCount("1,000")).toBe(1000);
    expect(parseCount("1.5")).toBeNull();
    expect(parseCount("")).toBeNull();
  });
});

describe("searchText", () => {
  it("keeps a number the router read from the address as text", () => {
    expect(searchText(8801712345678)).toBe("8801712345678");
    expect(searchText("রহিমা")).toBe("রহিমা");
    expect(searchText("")).toBeUndefined();
    expect(searchText(undefined)).toBeUndefined();
  });
});

describe("days", () => {
  it("counts today in Dhaka, wherever the phone is", () => {
    expect(dhakaToday(new Date("2026-10-31T17:59:00Z"))).toBe("2026-10-31");
    expect(dhakaToday(new Date("2026-10-31T18:00:00Z"))).toBe("2026-11-01");
  });

  it("moves across months and years", () => {
    expect(addDays("2026-11-01", -6)).toBe("2026-10-26");
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
    expect(addDays("2028-03-01", -1)).toBe("2028-02-29");
  });

  it("takes only a day from the address", () => {
    expect(dayText("2026-10-31")).toBe("2026-10-31");
    expect(dayText("yesterday")).toBeUndefined();
    expect(dayText(20261031)).toBeUndefined();
  });
});
