import type { Browser, Page } from "@playwright/test";
import { joinAsStaff, newUserId, PASSWORD, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });

/** The second person, in a browser of their own. */
async function secondBrowser(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  return context.newPage();
}

const phone = () => `017${String(Math.floor(Math.random() * 1e8)).padStart(8, "0")}`;

/** The owner fills the form; the starting password shown once comes back. */
async function addMember(owner: Page, name: string, userId: string, role: "স্টাফ" | "ম্যানেজার"): Promise<string> {
  await label(owner, "নাম").fill(name);
  await label(owner, "ফোন নম্বর").fill(phone());
  await label(owner, "ইউজার আইডি").fill(userId);
  await expect(owner.getByText(`✓ ${userId} পাওয়া যাবে`)).toBeVisible();
  await owner.getByRole("radio", { name: new RegExp(`^${role}`) }).check();
  await owner.getByRole("button", { name: "সদস্য যোগ করুন" }).click();
  await expect(owner.getByRole("heading", { name: `${name} যোগ হয়েছেন` })).toBeVisible();
  const card = owner.getByRole("region", { name: `${name} যোগ হয়েছেন` });
  return ((await card.getByRole("definition").nth(1).textContent()) ?? "").trim();
}

/** Signs in with the starting password and sets their own on the page that asks. */
async function firstSignIn(member: Page, userId: string, starting: string, name: string) {
  await member.goto("/sign-in");
  await label(member, "ইউজার আইডি").fill(userId);
  await label(member, "পাসওয়ার্ড").fill(starting);
  await member.getByRole("button", { name: "লগ ইন" }).click();
  await expect(member).toHaveURL(/\/set-password$/);
  await expect(member.getByRole("heading", { name: `স্বাগতম, ${name}!` })).toBeVisible();
  await expectAccessible(member);
  await label(member, "নতুন পাসওয়ার্ড").fill(PASSWORD);
  await label(member, "আবার লিখুন").fill("something else");
  await member.getByRole("button", { name: "পাসওয়ার্ড রেখে ঢুকুন" }).click();
  await expect(member.getByText("দুটি পাসওয়ার্ড মিলছে না।")).toBeVisible();
  await label(member, "আবার লিখুন").fill(PASSWORD);
  await member.getByRole("button", { name: "পাসওয়ার্ড রেখে ঢুকুন" }).click();
  await expect(member.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
}

test("the owner adds a manager with a user ID, who sets their own password at the first sign-in", async ({
  page,
  browser,
}) => {
  await signUpWithBusiness(page.request, { business: "দলের দোকান" });
  await page.goto("/more");
  await page.getByRole("link", { name: "সদস্য" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "সদস্য" })).toBeVisible();
  await expectAccessible(page);

  const userId = newUserId();
  const starting = await addMember(page, "Karim Manager", userId, "ম্যানেজার");
  expect(starting).toMatch(/^[A-Za-z2-9]{4}-[A-Za-z2-9]{4}$/);
  await expect(page.getByText(/এসএমএস পাঠানো হয়েছে/)).toBeVisible();
  await expect(page.getByText("এই পাসওয়ার্ড আর দেখা যাবে না।")).toBeVisible();
  const row = page.getByRole("listitem").filter({ hasText: "Karim Manager" });
  await expect(row.getByText("এখনো ঢোকেননি")).toBeVisible();
  await expect(row.getByText(`আইডি ${userId}`, { exact: false })).toBeVisible();
  await expectAccessible(page);

  const manager = await secondBrowser(browser);
  await firstSignIn(manager, userId, starting, "Karim Manager");
  await expect(manager.getByRole("button", { name: /দলের দোকান/ })).toBeVisible();

  // A manager reaches the team and the business, but not the audit log or deleting.
  await manager.goto("/more");
  await expect(manager.getByRole("link", { name: "সদস্য" })).toBeVisible();
  await expect(manager.getByRole("link", { name: "কার্যক্রমের তালিকা" })).toBeHidden();
  await manager.goto("/settings/audit-log");
  await expect(manager).toHaveURL(/localhost:\d+\/$/);
  await manager.goto("/settings/business");
  await expect(manager.getByRole("heading", { level: 1, name: /দলের দোকান/ })).toBeVisible();
  await expect(manager.getByRole("button", { name: "ব্যবসা মুছে ফেলুন" })).toBeHidden();
  await expect(manager.getByText("শুধু মালিক বদলাতে পারেন।")).toBeVisible();
  // The rules are for the owner to change.
  await manager.goto("/settings/staff");
  await expect(manager.getByRole("button", { name: "বদলান", exact: true })).toBeHidden();

  // The owner sees them signed in, and it is in the log.
  await page.reload();
  await expect(page.getByRole("listitem").filter({ hasText: "Karim Manager" }).getByText("এখনো ঢোকেননি")).toBeHidden();
  await page.goto("/settings/audit-log");
  await expect(page.getByRole("listitem").filter({ hasText: "সদস্য যোগ করেছেন: Karim Manager" })).toBeVisible();
  await manager.context().close();
});

test("the activity log says who did what by day, narrowed by person, kind and words", async ({ page, browser }) => {
  await signUpWithBusiness(page.request, { business: "হিসাবের দোকান" });
  const staff = await joinAsStaff(page, browser);
  await page.goto("/settings/business");
  await page.getByLabel("ঠিকানা (ঐচ্ছিক)", { exact: true }).fill("মিরপুর, ঢাকা");
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("সংরক্ষণ হয়েছে।")).toBeVisible();

  await page.goto("/more");
  await page.getByRole("link", { name: "কার্যক্রমের তালিকা" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "কার্যক্রমের তালিকা" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: /^আজ,/ })).toBeVisible();
  const changed = page.getByRole("listitem").filter({ hasText: "আপনি ব্যবসার তথ্য বদলেছেন" });
  await expect(changed).toBeVisible();
  await changed.getByRole("button", { name: "কী বদলেছে" }).click();
  await expect(changed.getByText("মিরপুর, ঢাকা")).toBeVisible();
  await expectAccessible(page);

  // Narrowed by kind: only the team's entries, with the member named.
  await page.getByRole("group", { name: "কোন কাজ" }).getByRole("button", { name: "সদস্য" }).click();
  await expect(page).toHaveURL(/kind=TEAM/);
  await expect(page.getByRole("listitem").filter({ hasText: "সদস্য যোগ করেছেন: Sumi Staff" })).toBeVisible();
  await expect(changed).toBeHidden();
  // By a person who did nothing here: nothing, and a way back.
  await page.getByLabel("কে করেছেন").selectOption({ label: "সিস্টেম" });
  await expect(page.getByText("এই খোঁজে কোনো কাজ নেই")).toBeVisible();
  await page.getByRole("button", { name: "সব দেখুন" }).click();
  await expect(changed).toBeVisible();
  // Words in the details.
  await page.getByRole("searchbox", { name: "খুঁজুন" }).fill("মিরপুর");
  await page.getByRole("searchbox", { name: "খুঁজুন" }).press("Enter");
  await expect(page.getByRole("status").filter({ hasText: "এই খোঁজে ১টি কাজ" })).toBeVisible();
  await expectAccessible(page);
  await staff.context().close();
});

test("staff can't reach team or business pages, and a removed member is out of the business", async ({
  page,
  browser,
}) => {
  await signUpWithBusiness(page.request, { business: "সরানোর দোকান" });
  const staff = await joinAsStaff(page, browser);
  await staff.clock.install();
  await staff.goto("/");
  await expect(staff.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();

  await staff.goto("/more");
  await expect(staff.getByRole("link", { name: "সদস্য" })).toBeHidden();
  await staff.goto("/settings/staff");
  await expect(staff).toHaveURL(/localhost:\d+\/$/);
  await staff.goto("/settings/business");
  await expect(staff).toHaveURL(/localhost:\d+\/$/);

  // The owner removes them; within a minute the app notices and leaves the business.
  await page.goto("/settings/staff");
  await page.getByRole("button", { name: "Sumi Staff: আরও" }).click();
  await page.getByRole("menuitem", { name: "দল থেকে সরান" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "সরান" }).click();
  await expect(page.getByText("Sumi Staff-কে সরানো হয়েছে।")).toBeVisible();

  await staff.clock.fastForward(61_000);
  await expect(staff.getByText('আপনি আর "সরানোর দোকান" ব্যবসার সদস্য নন।')).toBeVisible();
  await expect(staff).toHaveURL(/\/welcome$/);
  await staff.context().close();
});

test("changes a member's role, sends a new password, and hands over ownership", async ({ page, browser }) => {
  await signUpWithBusiness(page.request, { business: "হস্তান্তরের দোকান" });
  const member = await joinAsStaff(page, browser, { name: "Nila Next" });
  await page.goto("/settings/staff");

  const row = page.getByRole("listitem").filter({ hasText: "Nila Next" });
  const saved = page.waitForResponse((response) => response.request().method() === "PATCH");
  await row.getByRole("radio", { name: "ম্যানেজার" }).click();
  expect((await saved).status()).toBe(204);
  await expect(row.getByRole("radio", { name: "ম্যানেজার" })).toHaveAttribute("aria-checked", "true");

  // A new starting password, shown once; their old one stops working.
  await page.getByRole("button", { name: "Nila Next: আরও" }).click();
  await page.getByRole("menuitem", { name: "নতুন পাসওয়ার্ড পাঠান" }).click();
  const sending = page.getByRole("dialog", { name: "Nila Next-কে নতুন পাসওয়ার্ড পাঠাবেন?" });
  await expectAccessible(page);
  await sending.getByRole("button", { name: "নতুন পাসওয়ার্ড পাঠান" }).click();
  const shown = page.getByRole("dialog", { name: "Nila Next-এর নতুন পাসওয়ার্ড" });
  await expect(shown.getByText("এই পাসওয়ার্ড আর দেখা যাবে না।")).toBeVisible();
  await shown.getByRole("button", { name: "বন্ধ করুন" }).first().click();
  await expect(row.getByText("এখনো ঢোকেননি")).toBeVisible();
  await member.goto("/orders");
  await expect(member).toHaveURL(/\/sign-in/);

  await page.getByRole("button", { name: "Nila Next: আরও" }).click();
  await page.getByRole("menuitem", { name: "মালিকানা দিন" }).click();
  const dialog = page.getByRole("dialog");
  await label(dialog, "বর্তমান পাসওয়ার্ড").fill("not-the-password");
  await dialog.getByRole("button", { name: "মালিক বানান" }).click();
  await expect(label(dialog, "বর্তমান পাসওয়ার্ড")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "বর্তমান পাসওয়ার্ড").fill(PASSWORD);
  await dialog.getByRole("button", { name: "মালিক বানান" }).click();
  await expect(page.getByText("Nila Next এখন মালিক।")).toBeVisible();

  // The old owner is a manager now: no audit log, and they can leave.
  await page.goto("/more");
  await expect(page.getByRole("link", { name: "কার্যক্রমের তালিকা" })).toBeHidden();
  await page.goto("/settings/account");
  await page.getByRole("button", { name: "ব্যবসা ছেড়ে দিন" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "ব্যবসা ছেড়ে দিন" }).click();
  await expect(page).toHaveURL(/\/welcome$/);
  await member.context().close();
});

test("the owner changes what staff may do, and it counts at once", async ({ page, browser }) => {
  await signUpWithBusiness(page.request, { business: "নিয়মের দোকান" });
  const staff = await joinAsStaff(page, browser);
  await staff.goto("/more");
  await expect(staff.getByRole("link", { name: "নষ্ট পণ্য" })).toBeVisible();

  await page.goto("/settings/staff");
  await page.getByRole("button", { name: "বদলান", exact: true }).click();
  const stock = page.getByRole("checkbox", { name: "স্টাফ: স্টক আর নষ্ট পণ্য লেখা" });
  await expect(stock).toBeChecked();
  await stock.uncheck();
  await page.getByRole("checkbox", { name: "স্টাফ: পণ্য যোগ ও দাম বদলানো" }).check();
  await expectAccessible(page);
  await page.getByRole("button", { name: "২টি বদল সংরক্ষণ" }).click();
  await expect(page.getByText("নিয়ম বদলানো হয়েছে।")).toBeVisible();
  await expect(page.getByRole("img", { name: "স্টাফ: স্টক আর নষ্ট পণ্য লেখা: পারেন না" })).toBeVisible();

  await staff.reload();
  await expect(staff.getByRole("link", { name: "নষ্ট পণ্য" })).toBeHidden();
  await staff.goto("/damages");
  await expect(staff).toHaveURL(/localhost:\d+\/$/);
  await staff.goto("/products/new");
  await expect(staff.getByRole("heading", { level: 1, name: "নতুন পণ্য" })).toBeVisible();

  // And back.
  await page.getByRole("button", { name: "বদলান", exact: true }).click();
  await page.getByRole("button", { name: "শুরুর নিয়মে ফিরিয়ে নিন" }).click();
  await page.getByRole("button", { name: "২টি বদল সংরক্ষণ" }).click();
  await expect(page.getByRole("img", { name: "স্টাফ: স্টক আর নষ্ট পণ্য লেখা: পারেন" })).toBeVisible();
  await staff.context().close();
});

test("deleting a business needs the password, shows the date, and can be undone", async ({ page }) => {
  await signUpWithBusiness(page.request, { business: "মোছার দোকান" });
  await page.goto("/settings/business");

  await page.getByRole("button", { name: "ব্যবসা মুছে ফেলুন" }).click();
  const dialog = page.getByRole("dialog", { name: '"মোছার দোকান" মুছে ফেলবেন?' });
  await expect(dialog).toContainText("০টি পণ্য, ০টি অর্ডার, ০ জন ক্রেতা");
  await expectAccessible(page);
  const password = label(dialog, "নিশ্চিত করতে পাসওয়ার্ড দিন");
  await password.fill("not-the-password");
  await dialog.getByRole("button", { name: "মুছে ফেলুন", exact: true }).click();
  await expect(password).toHaveAttribute("aria-invalid", "true");
  await password.fill(PASSWORD);
  await dialog.getByRole("button", { name: "মুছে ফেলুন", exact: true }).click();

  // On every page until undone.
  const banner = page.getByRole("status").filter({ hasText: "মোছার দোকান" });
  await expect(banner).toBeVisible();
  await expectAccessible(page);
  await page.goto("/");
  await expect(banner).toBeVisible();

  await banner.getByRole("button", { name: "মুছে ফেলা বাতিল করুন" }).click();
  await expect(banner).toBeHidden();
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
  await expect(banner).toBeHidden();
});
