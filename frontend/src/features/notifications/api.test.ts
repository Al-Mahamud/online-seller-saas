import { describe, expect, it } from "vitest";
import { notificationLink } from "./api";

const link = (type: string, payload: Record<string, unknown> = {}) =>
  notificationLink({ type, payload: payload as never });

describe("notificationLink", () => {
  it("leads to the thing a notice is about", () => {
    expect(link("stock.low", { productId: "p1" })).toEqual({ to: "/products/$productId", params: { productId: "p1" } });
    expect(link("import.finished", { importId: "j1" })).toEqual({ to: "/products/import", search: { job: "j1" } });
    expect(link("billing.paid", { invoiceId: "i1" })).toEqual({
      to: "/billing/invoices/$invoiceId",
      params: { invoiceId: "i1" },
    });
  });

  it("sends every other billing notice to the plan page", () => {
    expect(link("billing.grace")).toEqual({ to: "/billing" });
    expect(link("billing.paid")).toEqual({ to: "/billing" });
  });

  it("leads nowhere when it can't tell", () => {
    expect(link("stock.low")).toBeNull();
    expect(link("something.new", { productId: "p1" })).toBeNull();
  });
});
