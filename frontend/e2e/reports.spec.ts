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
  await expect(tile(page, "আজকের বিক্রি")).toContainText(taka(api.confirmed.value));
  await expect(tile(page, "আজকের বিক্রি")).toContainText(`${number(api.confirmed.orders)}টি অর্ডার`);
  await expect(tile(page, "পণ্যে মুনাফা")).toContainText(taka(api.profit.grossProfit));
  await expect(tile(page, "COD বাকি")).toContainText(taka(api.cod.outstanding));
  await expect(tile(page, "COD বাকি")).toContainText(`পাওনা ${taka(api.cod.withCourier)}`);
  await expect(
    page.getByRole("link", { name: new RegExp(`${number(api.lowStock)}টি পণ্যের স্টক কমে এসেছে`) }),
  ).toBeVisible();
  for (const [status, label, next] of [
    ["DRAFT", "খসড়া", " নিশ্চিত করুন"],
    ["CONFIRMED", "নিশ্চিত", " প্যাক করুন"],
    ["SHIPPED", "কুরিয়ারে", ` আজ গেছে ${number(api.shipped)}`],
    ["DELIVERED", "ডেলিভারি", ""],
    ["CANCELLED", "বাতিল", ""],
  ] as const) {
    const count = number(api.byStatus[status] as number);
    await expect(page.getByRole("link", { name: `${label} ${count}${next}`, exact: true })).toBeVisible();
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

  // A step of the journey leads to the work it counts.
  await page.getByRole("link", { name: /^নিশ্চিত / }).click();
  await expect(page).toHaveURL(/\/orders\?status=CONFIRMED/);
});

test("any day can be looked at, from the chart or by date", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await seedDay(page.request);
  await page.goto("/");
  await expect(tile(page, "আজকের বিক্রি")).toContainText("৳৪,৬২০");

  await page.locator("section").filter({ hasText: "গত ৭ দিনের বিক্রি" }).getByRole("button").nth(5).click();
  await expect(tile(page, "বিক্রি")).toContainText("৳০");
  await expect(page.getByText("এই দিনে কোনো বিক্রি নেই।")).toBeVisible();
  await expect(heading(page, "আজ")).toHaveCount(0);
  await expectAccessible(page);

  await page.getByRole("link", { name: "আজকে ফিরে যান" }).click();
  await expect(heading(page, "আজ")).toBeVisible();
  await expect(tile(page, "আজকের বিক্রি")).toContainText("৳৪,৬২০");

  await page.getByLabel("অন্য দিন দেখুন", { exact: true }).fill("2024-03-15");
  await expect(page).toHaveURL(/date=2024-03-15/);
  await expect(tile(page, "বিক্রি")).toContainText("৳০");
});

test("a shop with no orders is shown the first steps, then the whole page", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/");

  await expect(page.getByRole("heading", { level: 2, name: "শুরু করুন" })).toBeVisible();
  await expect(page.locator("section").filter({ hasText: "ডেলিভারি ও ফেরত" })).toHaveCount(0);
  await expectAccessible(page);
  await page.getByRole("link", { name: /প্রথম পণ্য যোগ করুন/ }).click();
  await expect(page).toHaveURL(/\/products\/new/);

  await seedDay(page.request);
  await page.goto("/");
  await expect(tile(page, "আজকের বিক্রি")).toContainText("৳৪,৬২০");
  await expect(page.getByRole("heading", { level: 2, name: "শুরু করুন" })).toHaveCount(0);
});

test("deliveries and returns come from the overview, per courier", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const product = await post(page.request, "/api/v1/products", { name: "কুর্তি", price: 150000, openingStock: 50 });
  const variantId = (product.variants as unknown as { id: string }[])[0]?.id as string;
  const ship = async (courier: string) => {
    const id = await order(page.request, variantId, 1);
    await post(page.request, `/api/v1/orders/${id}/confirm`);
    await post(page.request, `/api/v1/orders/${id}/ship`, { courier });
    return id;
  };
  for (const _ of [1, 2, 3]) {
    await post(page.request, `/api/v1/orders/${await ship("Pathao")}/deliver`);
  }
  await post(page.request, `/api/v1/orders/${await ship("Steadfast")}/deliver`);
  const failed = await ship("Steadfast");
  const lines = ((await (await page.request.get(`/api/v1/orders/${failed}`)).json()) as { items: { id: string }[] })
    .items;
  await post(page.request, `/api/v1/orders/${failed}/returns`, {
    kind: "FAILED_DELIVERY",
    reason: "বাসায় ছিলেন না",
    items: lines.map((line) => ({ orderItemId: line.id, quantity: 1, disposition: "RESTOCK" })),
  });
  await ship("Steadfast");

  await page.goto("/");
  const section = page.locator("section").filter({ has: page.getByRole("heading", { name: "ডেলিভারি ও ফেরত" }) });
  await expect(section).toContainText("গত ৩০ দিন, ৫টি পার্সেল");
  await expect(section).toContainText("৮০.০%");
  await expect(section).toContainText("২০.০%");
  await expect(section.getByRole("row", { name: "Pathao ০ ৩ ০ ০.০%" })).toBeVisible();
  await expect(section.getByRole("row", { name: "Steadfast ১ ১ ১ ৫০.০%" })).toBeVisible();
  await expect(section).toContainText("পার্সেল ফেরত এসেছে");
  // Nothing late and nothing low: the card says so in a line, and in English too.
  await expect(page.getByText("সব ঠিক আছে। এখন নজর দেওয়ার মতো কিছু নেই।")).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "English" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Delivery and returns" })).toBeVisible();
  await expect(page.locator("section").filter({ hasText: "Last 30 days, 5 parcels" })).toContainText("80.0%");
  await expect(page.getByText("All clear. Nothing needs your attention right now.")).toBeVisible();
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
  await expect(tile(staff, "আজকের বিক্রি")).toContainText("৳৪,৬২০");
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
  await page.getByRole("link", { name: "রিপোর্ট", exact: true }).click();
  await expect(heading(page, "রিপোর্ট")).toBeVisible();

  const sales = page.locator("section").filter({ has: page.getByRole("heading", { name: "বিক্রি", exact: true }) });
  const total = sales.getByRole("row", { name: /^মোট/ });
  await expect(total).toContainText("৳৪,৫০০");
  await expect(page.getByRole("region", { name: "কোন সময়ের হিসাব" })).toContainText("৳৪,৫০০");
  // The chart is one control: the arrow keys go along it, and it says what each day stands for.
  const chart = sales.getByRole("slider", { name: "সময় অনুযায়ী বিক্রি" });
  await expect(chart).toHaveAttribute("aria-valuetext", /৳৪,৫০০/);
  await chart.focus();
  await page.keyboard.press("ArrowLeft");
  await expect(chart).toHaveAttribute("aria-valuetext", /৳০, ০টি অর্ডার/);
  await page.keyboard.press("End");
  await expect(chart).toHaveAttribute("aria-valuetext", /৳৪,৫০০/);
  // And a tap or a pointer picks the column under it.
  await chart.click({ position: { x: 2, y: 20 } });
  await expect(chart).toHaveAttribute("aria-valuetext", /৳০, ০টি অর্ডার/);
  await expect(chart).toHaveAttribute("aria-valuenow", "0");
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

  // Its pie: the two parts of the money with something in them, with no way to tell them by colour alone.
  await expect(profit.getByRole("img")).toHaveAttribute("aria-label", /পণ্যের খরচ ৬০%.*পণ্যে মুনাফা ৪০%/);

  await page.getByLabel("তারিখ পর্যন্ত", { exact: true }).fill("2024-03-15");
  await page.getByLabel("তারিখ থেকে", { exact: true }).fill("2024-03-01");
  await expect(page.getByText("এই সময়ে কোনো বিক্রি নেই।")).toBeVisible();
  await page.getByRole("button", { name: "গত ৭ দিন" }).click();
  await expect(total).toContainText("৳৪,৫০০");

  // More than the reports span: the server's reason, on the page.
  await page.getByLabel("তারিখ থেকে", { exact: true }).fill("2020-01-01");
  await expect(sales.getByRole("alert")).toContainText("৩৬৬");
});

test("the sales pie: how sales split, up to five parts and the rest, and by category", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const sell = async (name: string, price: number, categoryId?: string) => {
    const made = await post(page.request, "/api/v1/products", { name, price, openingStock: 5, categoryId });
    const variantId = (made.variants as unknown as { id: string }[])[0]?.id as string;
    const id = await order(page.request, variantId, 1);
    await post(page.request, `/api/v1/orders/${id}/confirm`);
  };
  await page.goto("/reports");
  const share = page.getByRole("region", { name: "বিক্রির ভাগ" });
  await expect(page.getByText("এই সময়ে কোনো বিক্রি নেই।")).toBeVisible();
  await expect(share).toHaveCount(0);

  // One product: a sentence, since a whole circle says nothing.
  await sell("একটি", 100000);
  await page.reload();
  await expect(share.getByText("এই সময়ের সব বিক্রি একটি থেকে, ৳১,০০০।")).toBeVisible();
  await expect(share.getByRole("img")).toHaveCount(0);

  // Two: the pie, with the names and figures beside it.
  await sell("দুই", 300000);
  await page.reload();
  await expect(share.getByText("সবচেয়ে বেশি: দুই, ৭৫%")).toBeVisible();
  await expect(share.getByRole("img")).toHaveAttribute("aria-label", "বিক্রির ভাগ: দুই ৭৫%, একটি ২৫%");
  await share.getByRole("button", { name: /একটি/ }).click();
  await expect(share.getByText("একটি: ৳১,০০০, ২৫%")).toBeVisible();
  await expectAccessible(page);

  // Seven: five, and the rest together.
  for (const [name, price] of [
    ["তিন", 90000],
    ["চার", 80000],
    ["পাঁচ", 70000],
    ["ছয়", 60000],
    ["সাত", 50000],
  ] as const) {
    await sell(name, price);
  }
  await page.reload();
  await expect(share.getByRole("listitem")).toHaveCount(6);
  await expect(share.getByRole("button", { name: /অন্যান্য/ })).toContainText("৳১,১০০");

  // The switch is kept in the address.
  await share.getByRole("button", { name: "ক্যাটাগরি" }).click();
  await expect(page).toHaveURL(/share=category/);
  await expect(share.getByRole("button", { name: "ক্যাটাগরি" })).toHaveAttribute("aria-pressed", "true");
});

test("a report compares with the days before, and a plan that can't reach them says nothing", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await seedDay(page.request);
  await page.goto("/reports");
  const period = page.getByRole("region", { name: "কোন সময়ের হিসাব" });
  await expect(period.getByText("৩০ দিনের হিসাব")).toBeVisible();

  const business = (await (await page.request.get("/api/v1/business")).json()) as { id: string };
  expect((await page.request.post(`/dev/plan?businessId=${business.id}&code=STARTER`)).status()).toBe(204);
  // Sixty days are within Starter's ninety; the sixty before them aren't.
  const sixtyAgo = new Date(Date.now() - 59 * 24 * 60 * 60_000).toISOString().slice(0, 10);
  await page.getByLabel("তারিখ থেকে", { exact: true }).fill(sixtyAgo);
  await expect(period.getByText("৬০ দিনের হিসাব")).toBeVisible();
  await expect(period).toContainText("৳৪,৫০০");
  await expect(page.getByRole("dialog")).toHaveCount(0);
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
