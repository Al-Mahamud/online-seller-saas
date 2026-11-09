import { describe, expect, it } from "vitest";
import { banglaTime, formatDate, formatDateTime, formatNumber, formatTaka } from "./format";

describe("formatTaka", () => {
  it("shows Bangla digits with lakh grouping in Bangla", () => {
    expect(formatTaka(123456700, "bn")).toBe("৳১২,৩৪,৫৬৭");
  });

  it("shows Latin digits with lakh grouping in English", () => {
    expect(formatTaka(123456700, "en")).toBe("৳12,34,567");
  });

  it("shows poisha only when there are some", () => {
    expect(formatTaka(120050, "en")).toBe("৳1,200.50");
    expect(formatTaka(150000, "bn")).toBe("৳১,৫০০");
  });

  it("puts the sign before the taka symbol", () => {
    expect(formatTaka(-5000, "en")).toBe("−৳50");
  });
});

describe("formatNumber", () => {
  it("formats counts in each language", () => {
    expect(formatNumber(1500, "bn")).toBe("১,৫০০");
    expect(formatNumber(1500, "en")).toBe("1,500");
  });
});

describe("dates", () => {
  it("are shown in Dhaka time", () => {
    // 20:30 UTC is 02:30 the next day in Dhaka.
    expect(formatDate("2026-10-29T20:30:00Z", "en")).toBe("30 Oct 2026");
    expect(formatDateTime("2026-10-29T20:30:00Z", "en")).toMatch(/30 Oct 2026.*2:30\s?am/i);
  });

  it("use Bangla digits in Bangla", () => {
    expect(formatDate("2026-10-29T06:00:00Z", "bn")).toContain("২০২৬");
  });

  it("say the part of the day first in Bangla, with no AM or PM", () => {
    // Dhaka times: 02:30, 09:05, 12:00, 17:16, 19:45, 23:59.
    expect(formatDateTime("2026-10-29T20:30:00Z", "bn")).toMatch(/^৩০ অক্টো.*২০২৬, রাত ২:৩০$/);
    expect(banglaTime(new Date("2026-11-07T03:05:00Z"))).toBe("সকাল ৯:০৫");
    expect(banglaTime(new Date("2026-11-07T06:00:00Z"))).toBe("দুপুর ১২:০০");
    expect(banglaTime(new Date("2026-11-07T11:16:00Z"))).toBe("বিকাল ৫:১৬");
    expect(banglaTime(new Date("2026-11-07T13:45:00Z"))).toBe("সন্ধ্যা ৭:৪৫");
    expect(banglaTime(new Date("2026-11-07T17:59:00Z"))).toBe("রাত ১১:৫৯");
  });
});
