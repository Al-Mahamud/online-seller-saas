import { expect, test } from "@playwright/test";
import { expectAccessible } from "./support/axe";

/** The navigation people see: the bottom tabs on a phone, the sidebar on a laptop. */
function mainNavigation(page: import("@playwright/test").Page) {
  return page.getByRole("navigation", { name: /প্রধান|Main/ });
}

test("opens in Bangla with the four tabs", async ({ page }) => {
  await page.goto("/");

  await expect(page.locator("html")).toHaveAttribute("lang", "bn");
  await expect(page).toHaveTitle("মজুদ");
  await expect(page.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
  const navigation = mainNavigation(page);
  await expect(navigation).toHaveCount(1);
  for (const tab of ["আজ", "অর্ডার", "পণ্য", "আরও"]) {
    await expect(navigation.getByRole("link", { name: tab })).toBeVisible();
  }
  await expectAccessible(page);
});

test("moves between tabs and marks the open one", async ({ page }) => {
  await page.goto("/");
  const navigation = mainNavigation(page);

  await navigation.getByRole("link", { name: "পণ্য" }).click();

  await expect(page).toHaveURL(/\/products$/);
  await expect(page.getByRole("heading", { level: 1, name: "পণ্য" })).toBeVisible();
  await expect(navigation.getByRole("link", { name: "পণ্য" })).toHaveAttribute("aria-current", "page");
  await expect(navigation.getByRole("link", { name: "আজ" })).not.toHaveAttribute("aria-current", "page");
});

test("switches to English and remembers it", async ({ page }) => {
  await page.goto("/orders");

  await page.getByRole("button", { name: "English" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "বাংলা" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "অর্ডার" })).toBeVisible();
});

test("shows the new-order button on phones only", async ({ page, isMobile }) => {
  await page.goto("/");
  const newOrder = page.getByRole("link", { name: "নতুন অর্ডার" });

  if (isMobile) {
    await newOrder.click();
    await expect(page).toHaveURL(/\/orders\/new$/);
    await expect(page.getByRole("heading", { level: 1, name: "নতুন অর্ডার" })).toBeVisible();
  } else {
    await expect(newOrder).toBeHidden();
  }
});

test("says when a page does not exist", async ({ page }) => {
  await page.goto("/nowhere");

  await expect(page.getByText("এই পাতাটি পাওয়া যায়নি।")).toBeVisible();
  await page.getByRole("link", { name: "আজ-এ ফিরে যান" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
});

test("fits the screen without sideways scrolling", async ({ page }) => {
  await page.goto("/");

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
