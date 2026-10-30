import type { APIRequestContext, Page } from "@playwright/test";
import { joinAsStaff, newPhone, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const heading = (page: Page, name: string) => page.getByRole("heading", { level: 1, name });
/** The bell: in the header on a phone, in the sidebar on a laptop. */
const bell = (page: Page) => page.getByRole("link", { name: /^নোটিফিকেশন/ });

async function xsrf(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  return (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

async function post(request: APIRequestContext, path: string, data?: unknown) {
  const answer = await request.post(path, { headers: { "X-XSRF-TOKEN": await xsrf(request) }, data });
  expect(answer.ok(), `${path}: ${await answer.text()}`).toBe(true);
  return (await answer.json()) as Record<string, never>;
}

async function product(request: APIRequestContext, name: string, stock: number) {
  const created = await post(request, "/api/v1/products", { name, price: 150000, openingStock: stock });
  const [variant] = created.variants as unknown as { id: string }[];
  return { id: created.id as unknown as string, variantId: variant?.id as string };
}

/** Confirming an order takes stock, as a teammate would on another phone. */
async function sell(request: APIRequestContext, variantId: string, quantity: number) {
  const order = await post(request, "/api/v1/orders", {
    customer: { name: "রহিমা", phone: newPhone(), address: "বাসা ৪", districtId: 47 },
    items: [{ variantId, quantity }],
    deliveryCharge: 6000,
  });
  await post(request, `/api/v1/orders/${order.id}/confirm`);
}

test("a low-stock alert reaches the bell within a minute, and leads to the product", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "সুতি কুর্তি", 6);
  // The minute passes on the page's own clock, so the test proves the wait without sitting through it.
  await page.clock.install();
  await page.goto("/");
  await expect(bell(page)).toHaveAccessibleName("নোটিফিকেশন");

  await sell(page.request, kurti.variantId, 2);
  await page.clock.fastForward(61_000);
  await expect(bell(page)).toHaveAccessibleName("নোটিফিকেশন ১ না পড়া");
  await expectAccessible(page);

  await bell(page).click();
  await expect(heading(page, "নোটিফিকেশন")).toBeVisible();
  await expectAccessible(page);
  const notice = page.getByRole("link", { name: /সুতি কুর্তি-এর স্টক কমে গেছে/ });
  await expect(notice).toContainText("আর ৪টি আছে");
  await notice.click();

  await expect(heading(page, "সুতি কুর্তি")).toBeVisible();
  await expect(bell(page)).toHaveAccessibleName("নোটিফিকেশন");
});

test("marks all as read, and the titles follow the language", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const first = await product(page.request, "কুর্তি", 6);
  const second = await product(page.request, "শাড়ি", 6);
  await sell(page.request, first.variantId, 2);
  await sell(page.request, second.variantId, 2);

  await page.goto("/notifications");
  await expect(bell(page)).toHaveAccessibleName("নোটিফিকেশন ২ না পড়া");
  await expect(page.getByRole("listitem").filter({ hasText: "স্টক কমে গেছে" })).toHaveCount(2);

  await page.getByRole("button", { name: "English" }).first().click();
  await expect(page.getByRole("listitem").filter({ hasText: "is running low" }).first()).toBeVisible();
  await page.getByRole("button", { name: "বাংলা" }).first().click();

  await page.getByRole("button", { name: "সব পড়া হয়েছে" }).click();
  await expect(bell(page)).toHaveAccessibleName("নোটিফিকেশন");
  await expect(page.getByRole("button", { name: "সব পড়া হয়েছে" })).toHaveCount(0);
});

test("a payment's notice leads to its invoice", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const checkout = await post(page.request, "/api/v1/billing/checkout", { planCode: "STARTER", months: 1 });
  await page.goto(checkout.gatewayUrl as unknown as string);
  await page.getByRole("link", { name: "Pay", exact: true }).click();
  await expect(page.getByText("পেমেন্ট পাওয়া গেছে।", { exact: false }).first()).toBeVisible();

  await page.goto("/notifications");
  await page.getByRole("link", { name: /পেমেন্ট পাওয়া গেছে/ }).click();
  await expect(heading(page, checkout.number as unknown as string)).toBeVisible();
});

test("the low-stock email can be turned off, and stays off", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/notifications");
  await expect(page.getByText("এখনো কোনো নোটিফিকেশন নেই।")).toBeVisible();
  await page.getByRole("link", { name: "ইমেইলের পছন্দ" }).click();
  await expect(heading(page, "ইমেইলের পছন্দ")).toBeVisible();
  const digest = page.getByLabel("প্রতিদিন স্টক কমে আসা পণ্যের তালিকা");
  await expect(digest).toBeChecked();
  await expectAccessible(page);

  await digest.uncheck();
  await expect(page.getByText("সংরক্ষণ হয়েছে।")).toBeVisible();
  await page.reload();
  await expect(digest).not.toBeChecked();
  const saved = (await (await page.request.get("/api/v1/notifications/preferences")).json()) as Record<string, boolean>;
  expect(saved.lowStockDigestEmail).toBe(false);
});

test("staff don't get the managers' notices or the email choices", async ({ page, browser }) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "কুর্তি", 6);
  await sell(page.request, kurti.variantId, 2);
  const staff = await joinAsStaff(page, browser);

  await staff.goto("/notifications");
  await expect(staff.getByText("এখনো কোনো নোটিফিকেশন নেই।")).toBeVisible();
  await expect(bell(staff)).toHaveAccessibleName("নোটিফিকেশন");
  await expect(staff.getByRole("link", { name: "ইমেইলের পছন্দ" })).toHaveCount(0);
  await staff.goto("/settings/notifications");
  await expect(staff).toHaveURL(/\/more$/);
  await staff.context().close();
});
