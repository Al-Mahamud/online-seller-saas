import type { Browser, Page } from "@playwright/test";
import {
  newEmail,
  newUserId,
  PASSWORD,
  readCode,
  readInviteToken,
  signUp,
  signUpWithBusiness,
} from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });

/** The second person, in a browser of their own. */
async function secondBrowser(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  return context.newPage();
}

async function invite(owner: Page, to: string, role: "ম্যানেজার" | "স্টাফ") {
  await label(owner, "ফোন নম্বর বা ইমেইল").fill(to);
  await label(owner, "ভূমিকা").selectOption({ label: role });
  await owner.getByRole("button", { name: "আমন্ত্রণ পাঠান" }).click();
  await expect(owner.getByText(`${to}-এ আমন্ত্রণ পাঠানো হয়েছে।`)).toBeVisible();
}

/** Someone new opens the invite link, signs up with the invited email, and joins. */
async function joinAsNewPerson(owner: Page, member: Page, name: string, email: string) {
  const token = await readInviteToken(owner.request, email);
  await member.goto(`/invites#${token}`);
  await expect(member.getByRole("heading", { level: 1, name: /যোগ দিন/ })).toBeVisible();
  await expectAccessible(member);
  await member.getByRole("link", { name: "নতুন অ্যাকাউন্ট খুলুন" }).click();

  await label(member, "আপনার নাম").fill(name);
  await label(member, "ফোন নম্বর").fill(`017${String(Math.floor(Math.random() * 1e8)).padStart(8, "0")}`);
  await label(member, "ইউজার আইডি").fill(newUserId());
  await label(member, "ইমেইল (ঐচ্ছিক)").fill(email);
  await label(member, "পাসওয়ার্ড").fill(PASSWORD);
  await label(member, "পাসওয়ার্ড আবার দিন").fill(PASSWORD);
  await member.getByRole("radio", { name: "ইমেইল" }).check();
  await member.getByRole("button", { name: "এগিয়ে যান" }).click();
  await label(member, "৬ সংখ্যার কোড").fill(await readCode(owner.request, email));
  await member.getByRole("button", { name: "অ্যাকাউন্ট খুলুন" }).click();

  // Back on the invite, now signed in.
  await member.getByRole("button", { name: /যোগ দিন/ }).click();
  await expect(member.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
}

test("an owner invites a manager, who joins from the link; the manager can't reach owner pages", async ({
  page,
  browser,
}) => {
  await signUpWithBusiness(page.request, { business: "দলের দোকান" });
  await page.goto("/more");
  await page.getByRole("link", { name: "সদস্য" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "সদস্য" })).toBeVisible();
  await expectAccessible(page);

  const email = newEmail();
  await invite(page, email, "ম্যানেজার");
  await expect(page.getByText(email, { exact: true })).toBeVisible();
  await expectAccessible(page);

  const manager = await secondBrowser(browser);
  await joinAsNewPerson(page, manager, "Karim Manager", email);
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

  // The owner sees them join, and the invite is gone.
  await page.reload();
  await expect(page.getByText("Karim Manager")).toBeVisible();
  await expect(page.getByRole("button", { name: `${email}-এর আমন্ত্রণ বাতিল করুন` })).toBeHidden();
  await page.goto("/settings/audit-log");
  await expect(page.getByText("নতুন সদস্য যোগ দিয়েছেন")).toBeVisible();
  await expectAccessible(page);
  await manager.context().close();
});

test("staff can't reach team or business pages, and a removed member is out of the business", async ({
  page,
  browser,
}) => {
  await signUpWithBusiness(page.request, { business: "সরানোর দোকান" });
  await page.goto("/settings/staff");
  const email = newEmail();
  await invite(page, email, "স্টাফ");

  const staff = await secondBrowser(browser);
  await staff.clock.install();
  await joinAsNewPerson(page, staff, "Sumi Staff", email);

  await staff.goto("/more");
  await expect(staff.getByRole("link", { name: "সদস্য" })).toBeHidden();
  await staff.goto("/settings/staff");
  await expect(staff).toHaveURL(/localhost:\d+\/$/);
  await staff.goto("/settings/business");
  await expect(staff).toHaveURL(/localhost:\d+\/$/);

  // The owner removes them; within a minute the app notices and leaves the business.
  await page.reload();
  await page.getByRole("listitem").filter({ hasText: "Sumi Staff" }).getByRole("button", { name: "সরান" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "সরান" }).click();
  await expect(page.getByText("Sumi Staff-কে সরানো হয়েছে।")).toBeVisible();

  await staff.clock.fastForward(61_000);
  await expect(staff.getByText('আপনি আর "সরানোর দোকান" ব্যবসার সদস্য নন।')).toBeVisible();
  await expect(staff).toHaveURL(/\/welcome$/);
  await staff.context().close();
});

test("changes a member's role, and hands over ownership with the password", async ({ page, browser }) => {
  await signUpWithBusiness(page.request, { business: "হস্তান্তরের দোকান" });
  await page.goto("/settings/staff");
  const email = newEmail();
  await invite(page, email, "স্টাফ");
  const member = await secondBrowser(browser);
  await joinAsNewPerson(page, member, "Nila Next", email);
  await page.reload();

  const row = page.getByRole("listitem").filter({ hasText: "Nila Next" });
  const saved = page.waitForResponse((response) => response.request().method() === "PATCH");
  await row.getByRole("combobox").selectOption({ label: "ম্যানেজার" });
  expect((await saved).status()).toBe(204);

  await row.getByRole("button", { name: "মালিক বানান" }).click();
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

test("cancels an invite, after which its link says so", async ({ page, browser }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/settings/staff");
  const email = newEmail();
  await invite(page, email, "স্টাফ");
  const token = await readInviteToken(page.request, email);

  await page.getByRole("button", { name: `${email}-এর আমন্ত্রণ বাতিল করুন` }).click();
  await expect(page.getByRole("button", { name: `${email}-এর আমন্ত্রণ বাতিল করুন` })).toBeHidden();

  const stranger = await secondBrowser(browser);
  await stranger.goto(`/invites#${token}`);
  await expect(stranger.getByRole("alert")).toContainText("নতুন আমন্ত্রণ");
  await stranger.context().close();
});

test("someone signed in with another account is told whose invite it is", async ({ page, browser }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/settings/staff");
  const email = newEmail();
  await invite(page, email, "স্টাফ");
  const token = await readInviteToken(page.request, email);

  const other = await secondBrowser(browser);
  await signUp(other.request);
  await other.goto(`/invites#${token}`);
  await other.getByRole("button", { name: /যোগ দিন/ }).click();

  await expect(other.getByRole("alert")).toContainText("লগ ইন করুন");
  await other.context().close();
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
