import type { APIRequestContext, Browser, Page } from "@playwright/test";
import { PASSWORD, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";
import { clearOfStepEnd, totp } from "./support/totp";

const heading = (page: Page, name: string | RegExp) => page.getByRole("heading", { level: 1, name });
const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });
const adminNav = (page: Page) => page.getByRole("navigation", { name: "অ্যাডমিন" });

async function xsrf(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  return (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

async function post(request: APIRequestContext, path: string, data?: unknown) {
  const answer = await request.post(path, { headers: { "X-XSRF-TOKEN": await xsrf(request) }, data });
  expect(answer.ok(), `${path}: ${await answer.text()}`).toBe(true);
  return answer;
}

/** Someone given admin rights who signed in since, without two-step sign-in yet. */
async function adminWithoutTwoStep(page: Page) {
  const account = await signUpWithBusiness(page.request, { business: "অ্যাডমিনের দোকান" });
  expect((await page.request.post(`/dev/platform-admin?phone=${account.phone}`)).status()).toBe(204);
  await post(page.request, "/api/v1/auth/logout");
  await post(page.request, "/api/v1/auth/login", { userId: account.userId, password: PASSWORD });
  return account;
}

async function turnOnTwoStep(page: Page) {
  const setup = await post(page.request, "/api/v1/me/two-factor/setup", { password: PASSWORD });
  const { secret } = (await setup.json()) as { secret: string };
  await clearOfStepEnd();
  await post(page.request, "/api/v1/me/two-factor/enable", { code: totp(secret, -1) });
}

async function admin(page: Page) {
  await adminWithoutTwoStep(page);
  await turnOnTwoStep(page);
}

/** A seller, in a browser of their own. */
async function seller(browser: Browser, business: string) {
  const context = await browser.newContext({
    baseURL: test.info().project.use.baseURL,
    locale: "bn-BD",
    timezoneId: "Asia/Dhaka",
    viewport: test.info().project.use.viewport,
  });
  const page = await context.newPage();
  const account = await signUpWithBusiness(page.request, { business });
  return { page, account };
}

test("without two-step sign-in, an admin sees the reminder and no data", async ({ page }) => {
  await adminWithoutTwoStep(page);
  await page.goto("/more");
  await page.getByRole("link", { name: "অ্যাডমিন" }).click();

  await expect(heading(page, "দুই ধাপের লগ ইন লাগবে")).toBeVisible();
  await expect(adminNav(page)).toHaveCount(0);
  await expectAccessible(page);
  expect((await page.request.get("/api/v1/admin/businesses")).status()).toBe(403);
  await page.getByRole("link", { name: "দুই ধাপের লগ ইন চালু করুন" }).click();
  await expect(page).toHaveURL(/\/settings\/security$/);
});

test("people who aren't admins never reach it, and have no way in", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/more");
  await expect(page.getByRole("link", { name: "অ্যাডমিন" })).toHaveCount(0);
  await page.goto("/admin");
  await expect(page).toHaveURL(/localhost:\d+\/$/);
  expect((await page.request.get("/api/v1/admin/businesses")).status()).toBe(403);
});

test("finds a business, and suspending it shows that business the banner", async ({ page, browser }) => {
  const shop = `স্থগিতের দোকান ${Math.random().toString(36).slice(2, 8)}`;
  const { page: owner, account } = await seller(browser, shop);
  await admin(page);
  await page.goto("/admin");
  await expect(heading(page, "ব্যবসা")).toBeVisible();
  await expectAccessible(page);

  // By the owner's phone, however it's written; by part of the name.
  const search = page.getByRole("searchbox", { name: "ব্যবসা খুঁজুন" });
  await search.fill(account.phone);
  await expect(page.getByRole("link", { name: new RegExp(shop) })).toBeVisible();
  await expect(page.getByRole("link", { name: /অ্যাডমিনের দোকান/ })).toHaveCount(0);
  await search.fill(shop.slice(-6));
  await page.getByRole("link", { name: new RegExp(shop) }).click();
  await expect(heading(page, shop)).toBeVisible();
  await expect(page.getByText("ফ্রি ট্রায়াল")).toBeVisible();
  // As people write it, in the language on screen.
  const shown = `${account.phone.slice(0, 5)}-${account.phone.slice(5)}`.replace(
    /\d/g,
    (digit) => "০১২৩৪৫৬৭৮৯"[Number(digit)] as string,
  );
  await expect(page.getByText(shown)).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "স্থগিত করুন" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "স্থগিত করুন" }).click();
  await expect(label(dialog, "কারণ")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "কারণ").fill("প্রতারণার অভিযোগ");
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "স্থগিত করুন" }).click();
  await expect(page.getByText("ব্যবসাটি স্থগিত হয়েছে।")).toBeVisible();
  await expect(page.getByText("reason: প্রতারণার অভিযোগ")).toBeVisible();

  await owner.goto("/");
  await expect(owner.getByText("এই ব্যবসাটি স্থগিত করা হয়েছে", { exact: false }).first()).toBeVisible();

  await page.getByRole("button", { name: "স্থগিত তুলে নিন" }).click();
  await expect(page.getByText("স্থগিত তুলে নেওয়া হয়েছে।")).toBeVisible();
  await owner.reload();
  await expect(owner.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
  await expect(owner.getByText("এই ব্যবসাটি স্থগিত করা হয়েছে", { exact: false })).toHaveCount(0);
  await owner.context().close();
});

test("extends a trial and sets a plan", async ({ page, browser }) => {
  const shop = `ট্রায়ালের দোকান ${Math.random().toString(36).slice(2, 8)}`;
  const { page: owner } = await seller(browser, shop);
  const before = (await (await owner.request.get("/api/v1/billing")).json()) as { trialEndsAt: string };
  await admin(page);
  await page.goto(`/admin?q=${encodeURIComponent(shop)}`);
  await page.getByRole("link", { name: new RegExp(shop) }).click();

  await page.getByRole("button", { name: "ট্রায়াল বাড়ান" }).click();
  await label(page.getByRole("dialog"), "কত দিন (১ থেকে ৯০)").fill("10");
  await page.getByRole("dialog").getByRole("button", { name: "ট্রায়াল বাড়ান" }).click();
  await expect(page.getByText("ট্রায়াল বাড়ানো হয়েছে।")).toBeVisible();
  const after = (await (await owner.request.get("/api/v1/billing")).json()) as { trialEndsAt: string };
  expect(new Date(after.trialEndsAt).getTime() - new Date(before.trialEndsAt).getTime()).toBe(10 * 24 * 60 * 60_000);

  await page.getByRole("button", { name: "প্ল্যান দিন" }).click();
  await label(page.getByRole("dialog"), "প্ল্যান").selectOption("PRO");
  await page.getByRole("dialog").getByRole("button", { name: "প্ল্যান দিন" }).click();
  await expect(page.getByText("প্ল্যান দেওয়া হয়েছে।")).toBeVisible();
  const billing = (await (await owner.request.get("/api/v1/billing")).json()) as {
    status: string;
    plan: { code: string };
  };
  expect(billing).toMatchObject({ status: "ACTIVE", plan: { code: "PRO" } });
  await owner.context().close();
});

test("marks a bank transfer paid, and the plan starts", async ({ page, browser }) => {
  const shop = `ব্যাংকের দোকান ${Math.random().toString(36).slice(2, 8)}`;
  const { page: owner } = await seller(browser, shop);
  const opened = await post(owner.request, "/api/v1/billing/checkout", {
    planCode: "STARTER",
    months: 1,
    payBy: "BANK_TRANSFER",
  });
  const { number } = (await opened.json()) as { number: string };
  await admin(page);
  await page.goto("/admin");
  await adminNav(page).getByRole("link", { name: "ব্যাংক ট্রান্সফার" }).click();
  await expect(page.getByRole("link", { name: shop })).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: `পরিশোধিত লিখুন: ${number}` }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(shop);
  await label(dialog, "ব্যাংকের রেফারেন্স").fill("DBBL-778812");
  await dialog.getByRole("button", { name: "পরিশোধিত লিখুন" }).click();
  await expect(page.getByText("পরিশোধিত লেখা হয়েছে।")).toBeVisible();
  await expect(page.getByRole("button", { name: `পরিশোধিত লিখুন: ${number}` })).toHaveCount(0);

  const billing = (await (await owner.request.get("/api/v1/billing")).json()) as {
    status: string;
    plan: { code: string };
  };
  expect(billing).toMatchObject({ status: "ACTIVE", plan: { code: "STARTER" } });

  // The accountant's month, with it in.
  const [file] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: "ডাউনলোড করুন" }).click(),
  ]);
  expect(file.suggestedFilename()).toMatch(/^ledger-\d{4}-\d{2}\.csv$/);
  await owner.context().close();

  // And in the admin log, by whom.
  await adminNav(page).getByRole("link", { name: "অ্যাডমিন লগ" }).click();
  const entry = page
    .getByRole("listitem")
    .filter({ hasText: "ব্যাংক ট্রান্সফার পরিশোধিত" })
    .filter({ hasText: "DBBL-778812" })
    .first();
  await expect(entry).toContainText("Test Seller");
  await expectAccessible(page);
});

test("shows the metrics, and edits a plan for those who choose one next", async ({ page }) => {
  await admin(page);
  await page.goto("/admin/metrics");
  await expect(heading(page, "হিসাব")).toBeVisible();
  await expect(page.getByText("মাসিক আয় (MRR)")).toBeVisible();
  await expectAccessible(page);

  await adminNav(page).getByRole("link", { name: "প্ল্যান" }).click();
  await expect(heading(page, "প্ল্যান")).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("button", { name: "বদলান: Starter" }).click();
  const dialog = page.getByRole("dialog");
  const price = label(dialog, "মাসিক দাম (৳)");
  const original = await price.inputValue();
  await price.fill("abc");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(price).toHaveAttribute("aria-invalid", "true");
  await expectAccessible(page);

  // Same price, a changed limit that's put back: other tests share these plans.
  await price.fill(original);
  const seats = label(dialog, "সদস্য");
  const seatsBefore = await seats.inputValue();
  await seats.fill(String(Number(seatsBefore) + 1));
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("প্ল্যান সংরক্ষণ হয়েছে।")).toBeVisible();

  await page.getByRole("button", { name: "বদলান: Starter" }).click();
  await expect(label(dialog, "সদস্য")).toHaveValue(String(Number(seatsBefore) + 1));
  await label(dialog, "সদস্য").fill(seatsBefore);
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(dialog).toHaveCount(0);
});
