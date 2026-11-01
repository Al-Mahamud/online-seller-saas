import type { APIRequestContext, Page } from "@playwright/test";
import { joinAsStaff, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const heading = (page: Page, name: string | RegExp) => page.getByRole("heading", { level: 1, name });
const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });
const banner = (page: Page) =>
  page
    .getByRole("status")
    .filter({ hasText: /ট্রায়াল|প্ল্যান|স্থগিত/ })
    .first();
const taka = (poisha: number) => `৳${new Intl.NumberFormat("bn-BD").format(poisha / 100)}`;

async function businessId(request: APIRequestContext): Promise<string> {
  return ((await (await request.get("/api/v1/business")).json()) as { id: string }).id;
}

/** Puts the business in a status, as time and the daily job would. */
async function setStatus(request: APIRequestContext, status: string, query = "") {
  const answer = await request.post(
    `/dev/subscription?businessId=${await businessId(request)}&status=${status}${query}`,
  );
  expect(answer.status()).toBe(204);
}

/** The plan's card, by its name as the server gives it. */
async function planCard(page: Page, code: string) {
  const plans = (await (await page.request.get("/api/v1/plans")).json()) as {
    code: string;
    nameBn: string;
    priceMonthly: number;
  }[];
  const plan = plans.find((one) => one.code === code);
  if (!plan) {
    throw new Error(`No plan ${code}`);
  }
  const card = page
    .getByRole("listitem")
    .filter({ has: page.getByRole("heading", { level: 3, name: plan.nameBn, exact: true }) });
  return { card, ...plan };
}

test("pays through the gateway, waits for the confirmation, and the plan becomes active", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/more");
  await page.getByRole("link", { name: "প্ল্যান ও পেমেন্ট" }).click();
  await expect(heading(page, "প্ল্যান ও পেমেন্ট")).toBeVisible();
  await expect(page.getByText("ফ্রি ট্রায়াল", { exact: true })).toBeVisible();
  await expect(page.getByText("এখনো কোনো ইনভয়েস নেই।")).toBeVisible();
  await expectAccessible(page);

  const starter = await planCard(page, "STARTER");
  await starter.card.getByRole("button", { name: "এই প্ল্যান নিন" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("status")).toContainText(taka(starter.priceMonthly));
  await dialog.getByLabel("৬ মাস").check();
  await expect(dialog.getByRole("status")).toContainText(taka(starter.priceMonthly * 6));
  await expectAccessible(page);

  // The browser's return proves nothing: the page waits until the server says it's paid.
  let answers = 0;
  await page.route("**/api/v1/billing/invoices/*", async (route) => {
    const response = await route.fetch();
    answers += 1;
    if (answers <= 2) {
      await route.fulfill({ response, json: { ...(await response.json()), status: "OPEN", paidAt: null } });
    } else {
      await route.fulfill({ response });
    }
  });
  await dialog.getByRole("button", { name: "পেমেন্টে যান" }).click();
  await expect(page.getByRole("heading", { name: "Test gateway" })).toBeVisible();
  await page.getByRole("link", { name: "Pay", exact: true }).click();
  await expect(page).toHaveURL(/\/billing\/return\?result=success/);
  await expect(page.getByText("পেমেন্ট নিশ্চিত হওয়ার অপেক্ষায় আছি।", { exact: false })).toBeVisible();
  await expect(page.getByText(`${taka(starter.priceMonthly * 6)} পেমেন্ট পাওয়া গেছে।`, { exact: false })).toBeVisible({
    timeout: 15_000,
  });
  await expectAccessible(page);

  await page.getByRole("link", { name: "প্ল্যান পাতায় যান" }).click();
  await expect(page.getByText("চালু", { exact: true })).toBeVisible();
  await expect(page.getByText(/পর্যন্ত পরিশোধিত/)).toBeVisible();
  const billing = (await (await page.request.get("/api/v1/billing")).json()) as {
    status: string;
    plan: { code: string };
  };
  expect(billing).toMatchObject({ status: "ACTIVE", plan: { code: "STARTER" } });

  // The invoice, and its page to print.
  await page.getByRole("link", { name: /^MJ-/ }).click();
  await expect(heading(page, /^MJ-/)).toBeVisible();
  await expect(page.getByText("পরিশোধিত", { exact: false }).first()).toBeVisible();
  await expectAccessible(page);
  const [printed] = await Promise.all([
    page.waitForEvent("popup"),
    page.getByRole("link", { name: "প্রিন্ট করুন" }).click(),
  ]);
  await expect(printed.getByText(/MJ-/).first()).toBeVisible();
});

test("a failed or cancelled payment changes nothing, and says so", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const pay = async (choice: string) => {
    await page.goto("/billing");
    await (await planCard(page, "STARTER")).card.getByRole("button", { name: "এই প্ল্যান নিন" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "পেমেন্টে যান" }).click();
    await page.getByRole("link", { name: choice, exact: true }).click();
  };

  await pay("Fail");
  await expect(page).toHaveURL(/result=fail/);
  await expect(page.getByText("পেমেন্ট হয়নি।", { exact: false })).toBeVisible();
  await pay("Cancel");
  await expect(page).toHaveURL(/result=cancel/);
  await expect(page.getByText("আপনি পেমেন্ট বাতিল করেছেন।", { exact: false })).toBeVisible();

  await page.goto("/billing");
  await expect(page.getByText("ফ্রি ট্রায়াল", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: /^MJ-/ }).first().click();
  await expect(page.getByText("এই পেমেন্ট সম্পন্ন হয়নি।", { exact: false })).toBeVisible();
});

test("a bank transfer gives the instructions, and they're still there later", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/billing");
  const starter = await planCard(page, "STARTER");
  await starter.card.getByRole("button", { name: "এই প্ল্যান নিন" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("ব্যাংক ট্রান্সফার").check();
  await dialog.getByRole("button", { name: "ব্যাংকের তথ্য দেখুন" }).click();

  await expect(heading(page, /^MJ-/)).toBeVisible();
  const number = (await heading(page, /^MJ-/).textContent()) as string;
  await expect(page.getByText(`${taka(starter.priceMonthly)} ব্যাংকে পাঠান`)).toBeVisible();
  await expect(page.getByText(`Reference: ${number}`, { exact: false })).toBeVisible();
  await expectAccessible(page);

  await page.goto("/billing");
  await expect(page.getByText("ফ্রি ট্রায়াল", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: new RegExp(number) }).click();
  await expect(page.getByText(`Reference: ${number}`, { exact: false })).toBeVisible();
});

test("upgrades for the prorated amount, and downgrades wait for the renewal", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await setStatus(page.request, "ACTIVE", "&plan=STARTER&days=10");
  await page.goto("/billing");

  // The amount on screen is what is charged.
  const pro = await planCard(page, "PRO");
  const quote = (await (await page.request.get("/api/v1/billing/quote?planCode=PRO")).json()) as { amount: number };
  expect(quote.amount).toBeGreaterThan(0);
  expect(quote.amount).toBeLessThan(pro.priceMonthly);
  await pro.card.getByRole("button", { name: "আপগ্রেড করুন" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("status")).toContainText(taka(quote.amount));
  await dialog.getByRole("button", { name: "পেমেন্টে যান" }).click();
  await page.getByRole("link", { name: "Pay", exact: true }).click();
  await expect(page.getByText(`${taka(quote.amount)} পেমেন্ট পাওয়া গেছে।`, { exact: false })).toBeVisible();
  const after = (await (await page.request.get("/api/v1/billing")).json()) as { plan: { code: string } };
  expect(after.plan.code).toBe("PRO");

  await page.goto("/billing");
  const starter = await planCard(page, "STARTER");
  await starter.card.getByRole("button", { name: "পরের নবায়নে বদলান" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "পরের নবায়নে বদলান" }).click();
  await expect(page.getByText(new RegExp(`${starter.nameBn} প্ল্যানে যাবে`))).toBeVisible();
  await starter.card.getByRole("button", { name: "বর্তমান প্ল্যানই রাখুন" }).click();
  await expect(page.getByText(new RegExp(`${starter.nameBn} প্ল্যানে যাবে`))).toHaveCount(0);
});

test("read-only: a banner, the server refuses what the screen didn't stop, and paying turns it back on", async ({
  page,
  browser,
}) => {
  await signUpWithBusiness(page.request);
  const staff = await joinAsStaff(page, browser);
  await setStatus(page.request, "READ_ONLY");
  await page.goto("/");
  await expect(banner(page)).toContainText("দোকানটি এখন শুধু দেখা যাবে");
  await expectAccessible(page);
  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "অর্ডার" })).toBeVisible();
  await expect(page.getByRole("link", { name: "নতুন অর্ডার" })).toHaveCount(0);

  // Reading still works; a form that would be refused says so before anything is typed.
  await page.goto("/orders/new");
  await expect(page.getByText("নতুন অর্ডার নেওয়া যাবে না", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "খসড়া রাখুন" })).toBeDisabled();

  // A write the screen doesn't stop: refused by the server, with its reason.
  await page.goto("/customers");
  await page.getByRole("button", { name: "নতুন গ্রাহক" }).first().click();
  const dialog = page.getByRole("dialog");
  await label(dialog, "গ্রাহকের নাম").fill("করিম");
  await label(dialog, "ফোন নম্বর").fill("01712345678");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(dialog.getByRole("alert")).toContainText("শুধু দেখা যাবে");
  await page.keyboard.press("Escape");
  const customers = (await (await page.request.get("/api/v1/customers")).json()) as { items: unknown[] };
  expect(customers.items).toHaveLength(0);

  // Staff are told whom to ask, and don't get the billing pages.
  await staff.goto("/");
  await expect(banner(staff)).toContainText("মালিককে প্ল্যান নিতে বলুন");
  await expect(staff.getByRole("link", { name: "প্ল্যান দেখুন" })).toHaveCount(0);
  await staff.goto("/billing");
  await expect(staff).toHaveURL(/localhost:\d+\/$/);
  await staff.context().close();

  // The owner pays, and the shop is back without a reload.
  await page.getByRole("link", { name: "প্ল্যান দেখুন" }).click();
  await (await planCard(page, "STARTER")).card.getByRole("button", { name: "নবায়ন করুন" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "পেমেন্টে যান" }).click();
  await page.getByRole("link", { name: "Pay", exact: true }).click();
  await expect(page.getByText("পেমেন্ট পাওয়া গেছে।", { exact: false })).toBeVisible();
  await expect(page.getByText("দোকানটি এখন শুধু দেখা যাবে")).toHaveCount(0);
  await page.goto("/orders/new");
  await expect(page.getByRole("button", { name: "খসড়া রাখুন" })).toBeEnabled();
});

test("a suspended business still opens, says why, and can't pay its way out", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await setStatus(page.request, "SUSPENDED");
  await page.goto("/");
  await expect(banner(page)).toContainText("এই ব্যবসাটি স্থগিত করা হয়েছে");
  await expect(page.getByRole("navigation", { name: "প্রধান" }).first()).toBeAttached();

  await page.goto("/billing");
  await expect(page.getByText("স্থগিত", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "এই প্ল্যান নিন" })).toHaveCount(0);
  await expectAccessible(page);
});

test("the banners warn of a trial's end and a payment due, and only then", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/");
  await expect(heading(page, "আজ")).toBeVisible();
  await expect(page.getByText("আপনার ফ্রি ট্রায়াল", { exact: false })).toHaveCount(0);

  await setStatus(page.request, "TRIALING", "&days=2");
  await page.reload();
  await expect(banner(page)).toContainText("আপনার ফ্রি ট্রায়াল");
  await expect(page.getByRole("link", { name: "প্ল্যান দেখুন" })).toBeVisible();

  await setStatus(page.request, "GRACE", "&days=5");
  await page.goto("/orders");
  await expect(banner(page)).toContainText("পেমেন্ট করুন");
  // Grace still works: the way to a new order stays.
  await expect(page.getByRole("link", { name: "নতুন অর্ডার" })).toBeVisible();
});
