import type { Page } from "@playwright/test";
import { joinAsStaff, signUp, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { png } from "./support/images";
import { expect, test } from "./support/test";

const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });
const switcher = (page: Page, business: string) => page.getByRole("button", { name: `ব্যবসা বদলান: ${business}` });

test("someone new creates their first business and lands on Today", async ({ page }) => {
  await signUp(page.request);
  await page.goto("/products");

  await expect(page).toHaveURL(/\/welcome$/);
  await expectAccessible(page);
  await page.getByRole("button", { name: "ব্যবসা খুলুন" }).click();
  await expect(page.getByText("এটি পূরণ করুন।")).toBeVisible();
  await label(page, "ব্যবসার ফোন নম্বর (ঐচ্ছিক)").fill("not a number");
  await label(page, "ব্যবসার নাম").fill("রহিমার কুর্তি হাউস");
  await page.getByRole("button", { name: "ব্যবসা খুলুন" }).click();
  await expect(label(page, "ব্যবসার ফোন নম্বর (ঐচ্ছিক)")).toHaveAttribute("aria-invalid", "true");
  await label(page, "ব্যবসার ফোন নম্বর (ঐচ্ছিক)").fill("০১৭১২৩৪৫৬৭৮");
  await page.getByRole("button", { name: "ব্যবসা খুলুন" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
  await expect(switcher(page, "রহিমার কুর্তি হাউস")).toBeVisible();
  await page.goto("/welcome");
  await expect(page.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
});

test("an owner can't open a second business; staff can open their own and switch between the two", async ({
  page,
  browser,
}) => {
  await signUpWithBusiness(page.request, { business: "প্রথম দোকান" });
  await page.goto("/");
  await switcher(page, "প্রথম দোকান").click();
  await expect(page.getByRole("menuitem", { name: /প্রথম দোকান/ })).toContainText("মালিক");
  await expect(page.getByRole("menuitem", { name: "নতুন ব্যবসা" })).toHaveCount(0);

  const staff = await joinAsStaff(page, browser);
  await staff.goto("/");
  await switcher(staff, "প্রথম দোকান").click();
  await expect(staff.getByRole("menuitem", { name: /প্রথম দোকান/ })).toContainText("স্টাফ");
  await expectAccessible(staff);
  await staff.getByRole("menuitem", { name: "নতুন ব্যবসা" }).click();
  const dialog = staff.getByRole("dialog");
  await label(dialog, "ব্যবসার নাম").fill("নিজের দোকান");
  await dialog.getByRole("button", { name: "ব্যবসা খুলুন" }).click();
  await expect(switcher(staff, "নিজের দোকান")).toBeVisible();

  await switcher(staff, "নিজের দোকান").click();
  await expect(staff.getByRole("menuitem", { name: /নিজের দোকান/ })).toContainText("মালিক");
  await expect(staff.getByRole("menuitem", { name: "নতুন ব্যবসা" })).toHaveCount(0);
  await staff.getByRole("menuitem", { name: /প্রথম দোকান/ }).click();
  await expect(switcher(staff, "প্রথম দোকান")).toBeVisible();

  await staff.reload();
  await expect(switcher(staff, "প্রথম দোকান")).toBeVisible();
  await staff.context().close();
});

test("edits the business's details and logo", async ({ page }) => {
  await signUpWithBusiness(page.request, { business: "পুরনো নাম" });
  await page.goto("/more");
  await page.getByRole("link", { name: "ব্যবসা", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: "ব্যবসা" })).toBeVisible();
  await expectAccessible(page);

  await label(page, "ব্যবসার নাম").fill("নতুন নাম");
  await label(page, "ঠিকানা (ঐচ্ছিক)").fill("মিরপুর, ঢাকা");
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).first().click();
  await expect(page.getByText("সংরক্ষণ করা হয়েছে।")).toBeVisible();
  await expect(switcher(page, "নতুন নাম")).toBeVisible();

  // Too small for the server, which says so.
  const logoInput = label(page, "লোগো আপলোড করুন");
  await logoInput.setInputFiles({ name: "tiny.png", mimeType: "image/png", buffer: png(10, 10) });
  await expect(page.getByText(/১০০/)).toBeVisible();

  await logoInput.setInputFiles({ name: "logo.png", mimeType: "image/png", buffer: png(200, 200) });
  const logo = page.getByRole("img", { name: "ব্যবসার লোগো" });
  await expect(logo).toBeVisible();
  await expect.poll(() => logo.evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
  await page.getByRole("button", { name: "লোগো সরান" }).click();
  await expect(logo).toBeHidden();

  await page.reload();
  await expect(label(page, "ঠিকানা (ঐচ্ছিক)")).toHaveValue("মিরপুর, ঢাকা");
});

test("changes the order and stock settings", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/settings/business");

  await expect(page.getByRole("radio", { name: /কুরিয়ারে দেওয়ার সময়/ })).toBeChecked();
  await page.getByRole("radio", { name: /অর্ডার নিশ্চিত করার সময়/ }).check();
  await label(page, "কম স্টকের সীমা").fill("১২");
  await label(page, "অর্ডার নম্বরের শুরুর অংশ").fill("rk-");
  await label(page, "স্লিপের নিচের লেখা").fill("ধন্যবাদ");
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).last().click();
  await expect(page.getByText("সংরক্ষণ করা হয়েছে।")).toBeVisible();

  await page.reload();
  await expect(page.getByRole("radio", { name: /অর্ডার নিশ্চিত করার সময়/ })).toBeChecked();
  await expect(label(page, "কম স্টকের সীমা")).toHaveValue("12");
  await expect(label(page, "অর্ডার নম্বরের শুরুর অংশ")).toHaveValue("RK-");
  await expect(label(page, "স্লিপের নিচের লেখা")).toHaveValue("ধন্যবাদ");

  await label(page, "কম স্টকের সীমা").fill("many");
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).last().click();
  await expect(label(page, "কম স্টকের সীমা")).toHaveAttribute("aria-invalid", "true");
});
