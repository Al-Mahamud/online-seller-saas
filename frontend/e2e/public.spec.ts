import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

// What anyone sees before signing in. Needs no test backend, so the deploy drill runs it too, against
// the real server behind Caddy.

test("sends someone signed out to sign-in, keeping the page", async ({ page }) => {
  await page.goto("/products");

  await expect(page).toHaveURL(/\/sign-in\?redirect=%2Fproducts$/);
  await expect(page.getByRole("heading", { level: 1, name: "আবার স্বাগতম" })).toBeVisible();
  await expect(page).toHaveTitle("মজুদ");
  await expect(page.locator("html")).toHaveAttribute("lang", "bn");
});

test("sign-in is accessible in both languages", async ({ page }) => {
  await page.goto("/sign-in");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "English" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "Welcome back" })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expectAccessible(page);
});

test("the sign-up and forgot-password pages open and are accessible", async ({ page }) => {
  await page.goto("/sign-in");

  await page.getByRole("link", { name: "নতুন অ্যাকাউন্ট খুলুন" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "নতুন অ্যাকাউন্ট" })).toBeVisible();
  await expectAccessible(page);

  await page.goto("/forgot-password");
  await expect(page.getByRole("heading", { level: 1, name: "পাসওয়ার্ড ভুলে গেছেন?" })).toBeVisible();
  await expectAccessible(page);
});

test("says when a page does not exist", async ({ page }) => {
  await page.goto("/sign-in/nowhere");

  await expect(page.getByRole("heading", { level: 1, name: "এই পাতাটি পাওয়া যায়নি।" })).toBeVisible();
});

test("fits the screen without sideways scrolling", async ({ page }) => {
  await page.goto("/sign-in");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
