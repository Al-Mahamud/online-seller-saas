import { readFileSync } from "node:fs";
import type { APIRequestContext, Page } from "@playwright/test";
import { joinAsStaff, newPhone, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const number = (value: number) => new Intl.NumberFormat("bn-BD").format(value);
const taka = (poisha: number) => `৳${number(poisha / 100)}`;
const heading = (page: Page, name: string) => page.getByRole("heading", { level: 1, name });
/** A tile with its label: its figure and note are in the same box. */
const tile = (page: Page, name: string) => page.getByText(name, { exact: true }).locator("..");

async function xsrf(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  return (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

async function post(request: APIRequestContext, path: string, data?: unknown) {
  const answer = await request.post(path, { headers: { "X-XSRF-TOKEN": await xsrf(request) }, data });
  expect(answer.ok(), `${path}: ${await answer.text()}`).toBe(true);
  return (await answer.json()) as Record<string, never>;
}

async function order(request: APIRequestContext, variantId: string, quantity: number, name = "রহিমা") {
  const made = await post(request, "/api/v1/orders", {
    customer: { name, phone: newPhone(), address: "বাসা ৪", districtId: 47 },
    items: [{ variantId, quantity }],
    deliveryCharge: 6000,
  });
  return made.id as unknown as string;
}

/**
 * A seeded day: ৳1,500 items costing ৳900, each order ৳60 delivery. Two confirmed orders count (2 items
 * and 1, ৳4,620 with delivery); a draft and a cancelled one don't.
 */
async function seedDay(request: APIRequestContext) {
  const product = await post(request, "/api/v1/products", {
    name: "কুর্তি",
    price: 150000,
    costPrice: 90000,
    openingStock: 20,
  });
  const variantId = (product.variants as unknown as { id: string }[])[0]?.id as string;
  const packing = await order(request, variantId, 2);
  await post(request, `/api/v1/orders/${packing}/confirm`);
  const delivered = await order(request, variantId, 1);
  await post(request, `/api/v1/orders/${delivered}/confirm`);
  await post(request, `/api/v1/orders/${delivered}/ship`, { courier: "Pathao", courierCharge: 5000 });
  await post(request, `/api/v1/orders/${delivered}/deliver`);
  await order(request, variantId, 5);
  const cancelled = await order(request, variantId, 4);
  await post(request, `/api/v1/orders/${cancelled}/confirm`);
  await post(request, `/api/v1/orders/${cancelled}/cancel`, { reason: "গ্রাহক চান না" });
  await post(request, "/api/v1/products", { name: "শেষের পথে", price: 50000, openingStock: 1 });
}

test("the day's figures on screen are the API's", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await seedDay(page.request);
  const api = (await (await page.request.get("/api/v1/dashboard/today")).json()) as {
    confirmed: { orders: number; value: number };
    byStatus: Record<string, number>;
    shipped: number;
    delivered: number;
    cod: { outstanding: number; withCourier: number; inTransit: number };
    lowStock: number;
    topProducts: { productName: string; quantity: number; value: number }[];
    profit: { revenue: number; cost: number; grossProfit: number };
  };
  // The API's own figures are what was seeded, so the comparison below means something.
  expect(api.confirmed).toEqual({ orders: 2, value: 462000 });
  expect(api.profit).toMatchObject({ revenue: 450000, cost: 270000, grossProfit: 180000 });

  await page.goto("/");
  await expect(heading(page, "আজ")).toBeVisible();
  await expect(tile(page, "বিক্রি")).toContainText(taka(api.confirmed.value));
  await expect(tile(page, "বিক্রি")).toContainText(`${number(api.confirmed.orders)}টি অর্ডার`);
  await expect(tile(page, "পণ্যে মুনাফা")).toContainText(taka(api.profit.grossProfit));
  await expect(tile(page, "প্যাক করতে হবে")).toContainText(number(api.byStatus.CONFIRMED as number));
  await expect(tile(page, "কুরিয়ারে আছে")).toContainText(number(api.byStatus.SHIPPED as number));
  await expect(tile(page, "কুরিয়ারে আছে")).toContainText(`গেছে ${number(api.shipped)}, পৌঁছেছে ${number(api.delivered)}`);
  await expect(tile(page, "COD বাকি")).toContainText(taka(api.cod.outstanding));
  await expect(tile(page, "COD বাকি")).toContainText(`পাওনা ${taka(api.cod.withCourier)}`);
  await expect(tile(page, "স্টক কম")).toContainText(number(api.lowStock));
  for (const [status, label] of [
    ["DRAFT", "খসড়া"],
    ["CONFIRMED", "নিশ্চিত"],
    ["DELIVERED", "ডেলিভারি হয়েছে"],
    ["CANCELLED", "বাতিল"],
  ] as const) {
    await expect(
      page.getByRole("link", { name: `${label} ${number(api.byStatus[status] as number)}`, exact: true }),
    ).toBeVisible();
  }
  const best = api.topProducts[0] as { productName: string; value: number };
  await expect(page.locator("section").filter({ hasText: "সবচেয়ে বেশি বিক্রি" })).toContainText(best.productName);
  await expect(page.locator("section").filter({ hasText: "সবচেয়ে বেশি বিক্রি" })).toContainText(taka(best.value));
  await expect(page.locator("section").filter({ hasText: "স্টক কমে আসছে" })).toContainText("শেষের পথে");

  // The chart's column for today says the same as the tile.
  const columns = page.locator("section").filter({ hasText: "গত ৭ দিনের বিক্রি" }).getByRole("button");
  await expect(columns).toHaveCount(7);
  await expect(columns.nth(6)).toHaveAttribute("aria-pressed", "true");
  await expect(columns.nth(6)).toHaveAccessibleName(new RegExp(`${taka(api.confirmed.value)}, ২টি অর্ডার`));
  await expectAccessible(page);

  // A tile leads to the work it counts.
  await tile(page, "প্যাক করতে হবে").click();
  await expect(page).toHaveURL(/\/orders\?status=CONFIRMED/);
});

test("any day can be looked at, from the chart or by date", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await seedDay(page.request);
  await page.goto("/");
  await expect(tile(page, "বিক্রি")).toContainText("৳৪,৬২০");

  await page.locator("section").filter({ hasText: "গত ৭ দিনের বিক্রি" }).getByRole("button").nth(5).click();
  await expect(tile(page, "বিক্রি")).toContainText("৳০");
  await expect(page.getByText("এই দিনে কোনো বিক্রি নেই।")).toBeVisible();
  await expect(heading(page, "আজ")).toHaveCount(0);
  await expectAccessible(page);

  await page.getByRole("link", { name: "আজকে ফিরে যান" }).click();
  await expect(heading(page, "আজ")).toBeVisible();
  await expect(tile(page, "বিক্রি")).toContainText("৳৪,৬২০");

  await page.getByLabel("অন্য দিন দেখুন", { exact: true }).fill("2024-03-15");
  await expect(page).toHaveURL(/date=2024-03-15/);
  await expect(tile(page, "বিক্রি")).toContainText("৳০");
});

test("staff see sales and no profit, and can't reach it", async ({ page, browser }) => {
  await signUpWithBusiness(page.request);
  await seedDay(page.request);
  const staff = await joinAsStaff(page, browser);

  const answer = (await (await staff.request.get("/api/v1/dashboard/today")).json()) as { profit: unknown };
  expect(answer.profit).toBeNull();
  expect((await staff.request.get("/api/v1/reports/profit")).status()).toBe(403);
  expect((await staff.request.get("/api/v1/exports/orders.csv")).status()).toBe(403);

  await staff.goto("/");
  await expect(tile(staff, "বিক্রি")).toContainText("৳৪,৬২০");
  await expect(staff.getByText("পণ্যে মুনাফা", { exact: true })).toHaveCount(0);

  await staff.goto("/reports");
  await expect(staff.getByRole("cell", { name: "৳৪,৫০০" }).first()).toBeVisible();
  await expect(staff.getByRole("heading", { name: "মুনাফা" })).toHaveCount(0);
  await expect(staff.getByRole("heading", { name: "ডাউনলোড" })).toHaveCount(0);
  await staff.context().close();
});

test("reports: sales by day, product and category, profit, and over chosen days", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await seedDay(page.request);
  await page.goto("/more");
  await page.getByRole("link", { name: "রিপোর্ট" }).click();
  await expect(heading(page, "রিপোর্ট")).toBeVisible();

  const sales = page.locator("section").filter({ has: page.getByRole("heading", { name: "বিক্রি" }) });
  const total = sales.getByRole("row", { name: /^মোট/ });
  await expect(total).toContainText("৳৪,৫০০");
  await expect(total).toContainText("৳১২০");
  await expect(sales.getByRole("row")).toHaveCount(3);
  await expectAccessible(page);

  await sales.getByRole("button", { name: "পণ্য অনুযায়ী" }).click();
  await expect(sales.getByRole("row", { name: /^কুর্তি/ })).toContainText("৳৪,৫০০");
  await expect(page).toHaveURL(/by=product/);
  await sales.getByRole("button", { name: "ক্যাটাগরি অনুযায়ী" }).click();
  await expect(sales.getByRole("columnheader", { name: "ক্যাটাগরি" })).toBeVisible();
  await expect(total).toContainText("৳৪,৫০০");

  const profit = page.locator("section").filter({ has: page.getByRole("heading", { name: "মুনাফা" }) });
  await expect(profit.getByText("পণ্যে মুনাফা").locator("..")).toContainText("৳১,৮০০");
  await expect(profit.getByText("কুরিয়ারের চার্জ").locator("..")).toContainText("৳৫০");
  await expect(profit.getByText("ডেলিভারিতে লাভ").locator("..")).toContainText("৳৭০");
  await expect(profit.getByText("মোট মুনাফা").locator("..")).toContainText("৳১,৮৭০");

  await page.getByLabel("তারিখ পর্যন্ত", { exact: true }).fill("2024-03-15");
  await page.getByLabel("তারিখ থেকে", { exact: true }).fill("2024-03-01");
  await expect(page.getByText("এই সময়ে কোনো বিক্রি নেই।")).toBeVisible();
  await page.getByRole("button", { name: "গত ৭ দিন" }).click();
  await expect(total).toContainText("৳৪,৫০০");

  // More than the reports span: the server's reason, on the page.
  await page.getByLabel("তারিখ থেকে", { exact: true }).fill("2020-01-01");
  await expect(sales.getByRole("alert")).toContainText("৩৬৬");
});

test("downloads: the orders of the chosen days, and the owner's whole copy", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await seedDay(page.request);
  await page.goto("/reports");

  const save = async (name: string) => {
    const [file] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name }).click()]);
    return { name: file.suggestedFilename(), path: await file.path() };
  };
  const orders = await save("অর্ডার (CSV)");
  expect(orders.name).toMatch(/^orders-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-\d{2}\.csv$/);
  expect(readFileSync(orders.path, "utf8")).toContain("#1001");
  expect(readFileSync((await save("পণ্য ও স্টক (CSV)")).path, "utf8")).toContain("কুর্তি");
  expect(readFileSync((await save("গ্রাহক (CSV)")).path, "utf8")).toContain("রহিমা");
  const everything = await save("ব্যবসার সব তথ্য (ZIP)");
  expect(everything.name).toBe("mojud-export.zip");
  expect(readFileSync(everything.path).subarray(0, 2).toString()).toBe("PK");
});

test("what the plan doesn't allow opens the upgrade dialog", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const business = (await (await page.request.get("/api/v1/business")).json()) as { id: string };
  expect((await page.request.post(`/dev/plan?businessId=${business.id}&code=STARTER`)).status()).toBe(204);

  // Starter: no exports, and reports reach back 90 days.
  await page.goto("/reports");
  await page.getByRole("button", { name: "অর্ডার (CSV)" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByRole("dialog")).toContainText("আপনার প্ল্যানে ডাউনলোড নেই।");
  await page.getByRole("dialog").getByRole("button", { name: "বন্ধ করুন" }).first().click();

  const longAgo = new Date(Date.now() - 120 * 24 * 60 * 60_000).toISOString().slice(0, 10);
  await page.getByLabel("তারিখ থেকে", { exact: true }).fill(longAgo);
  await expect(page.getByRole("dialog")).toContainText("গত ৯০ দিনের রিপোর্ট");
  await expect(page.getByRole("dialog").getByRole("link", { name: "প্ল্যান দেখুন" })).toBeVisible();
});
