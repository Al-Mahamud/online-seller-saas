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
  await expect(page.getByRole("heading", { level: 1, name: "পুরনো নাম" })).toBeVisible();
  await expectAccessible(page);

  // Typing shows at once in the header and on the slip; one bar saves it all.
  await label(page, "ব্যবসার নাম").fill("নতুন নাম");
  await label(page, "ঠিকানা (ঐচ্ছিক)").fill("মিরপুর, ঢাকা");
  await expect(page.getByRole("heading", { level: 1, name: "নতুন নাম" })).toBeVisible();
  const bar = page.getByRole("region", { name: "সংরক্ষণ হয়নি এমন পরিবর্তন" });
  await expect(bar).toContainText("২টি পরিবর্তন সংরক্ষণ হয়নি");
  await expectAccessible(page);
  await bar.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("সংরক্ষণ হয়েছে।")).toBeVisible();
  await expect(bar).toBeHidden();
  await expect(switcher(page, "নতুন নাম")).toBeVisible();

  // Too small for the server, which says so.
  const logoInput = label(page, "লোগো আপলোড করুন");
  await logoInput.setInputFiles({ name: "tiny.png", mimeType: "image/png", buffer: png(10, 10) });
  await expect(page.getByText(/ছবি কমপক্ষে ১০০/)).toBeVisible();

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

  // Fixed for now: stock goes down when handed to the courier, with nothing to pick.
  await expect(page.getByText("কুরিয়ারে দেওয়ার সময়", { exact: true })).toBeVisible();
  await expect(page.getByText("আপাতত সব ব্যবসায় এটিই নিয়ম, বদলানো যায় না।")).toBeVisible();
  await expect(page.getByRole("radio", { name: /অর্ডার নিশ্চিত করার সময়/ })).toHaveCount(0);
  await label(page, "কম স্টকের সীমা").fill("১২");
  await label(page, "অর্ডার নম্বরের শুরুর অংশ").fill("rk-");
  await label(page, "স্লিপের নিচের লেখা").fill("ধন্যবাদ");
  await expect(page.getByText("পরের অর্ডার: RK-১০০১")).toBeVisible();
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("সংরক্ষণ হয়েছে।")).toBeVisible();

  await page.reload();
  await expect(label(page, "কম স্টকের সীমা")).toHaveValue("12");
  await expect(label(page, "অর্ডার নম্বরের শুরুর অংশ")).toHaveValue("RK-");
  await expect(label(page, "স্লিপের নিচের লেখা")).toHaveValue("ধন্যবাদ");

  // The steppers change it too, and "বাতিল" puts it back.
  await page.getByRole("button", { name: "একটি বেশি" }).click();
  await expect(label(page, "কম স্টকের সীমা")).toHaveValue("13");
  await page.getByRole("button", { name: "বাতিল", exact: true }).click();
  await expect(label(page, "কম স্টকের সীমা")).toHaveValue("12");

  await label(page, "কম স্টকের সীমা").fill("many");
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(label(page, "কম স্টকের সীমা")).toHaveAttribute("aria-invalid", "true");
});

test("the owner picks the search panel's colours for every list", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/orders");
  const button = page.getByRole("search", { name: "অর্ডার খুঁজুন" }).getByRole("button", { name: "খুঁজুন" });
  await expect(button).toHaveCSS("background-color", "rgb(240, 185, 79)");

  await page.goto("/settings/business");
  const colours = page.getByRole("group", { name: "খোঁজার প্যানেলের রং" });
  await expect(colours.getByRole("radio", { name: /লেজার সবুজ/ })).toBeChecked();
  await expectAccessible(page);
  await colours.getByRole("radio", { name: /পুদিনা সবুজ/ }).check();
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("সংরক্ষণ হয়েছে।")).toBeVisible();

  await page.goto("/customers");
  await expect(page.getByRole("search", { name: "গ্রাহক খুঁজুন" }).getByRole("button", { name: "খুঁজুন" })).toHaveCSS(
    "background-color",
    "rgb(7, 57, 43)",
  );
  await expectAccessible(page);
  await page.reload();
  await expect(page.getByRole("search", { name: "গ্রাহক খুঁজুন" }).getByRole("button", { name: "খুঁজুন" })).toHaveCSS(
    "background-color",
    "rgb(7, 57, 43)",
  );
});
