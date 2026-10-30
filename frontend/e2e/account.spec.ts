import type { Page } from "@playwright/test";
import { newEmail, PASSWORD, readCode, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";
import { totp } from "./support/totp";

const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });

async function signOut(page: Page) {
  await page.goto("/more");
  await page.getByRole("button", { name: "লগ আউট" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "আবার স্বাগতম" })).toBeVisible();
}

async function signIn(page: Page, login: string, password: string) {
  await label(page, "ফোন নম্বর বা ইমেইল").fill(login);
  await label(page, "পাসওয়ার্ড").fill(password);
  await page.getByRole("button", { name: "লগ ইন", exact: true }).click();
}

const today = (page: Page) => page.getByRole("heading", { level: 1, name: "আজ" });

test("reaches the account pages from More", async ({ page }) => {
  await signUpWithBusiness(page.request, { name: "Rahima Khatun" });
  await page.goto("/more");

  await expect(page.getByText("Rahima Khatun")).toBeVisible();
  await page.getByRole("link", { name: "অ্যাকাউন্ট" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "অ্যাকাউন্ট" })).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("link", { name: "আরও" }).first().click();
  await page.getByRole("link", { name: "নিরাপত্তা" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "নিরাপত্তা" })).toBeVisible();
  await expectAccessible(page);
});

test("changes the name and the language", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/settings/account");

  await label(page, "নাম").fill("Rahima Khatun");
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("সংরক্ষণ করা হয়েছে।")).toBeVisible();

  const saved = page.waitForResponse(
    (response) => response.url().endsWith("/api/v1/me") && response.request().method() === "PATCH",
  );
  await page.getByRole("group", { name: "ভাষা" }).getByRole("button", { name: "English" }).click();
  await saved;
  await expect(page.getByRole("heading", { level: 1, name: "Account" })).toBeVisible();

  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Account" })).toBeVisible();
  await expect(label(page, "Name")).toHaveValue("Rahima Khatun");
});

test("adds an email with a code and the password", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const email = newEmail();
  await page.goto("/settings/account");

  await page.getByRole("button", { name: "যোগ করুন: ইমেইল" }).click();
  const dialog = page.getByRole("dialog");
  await label(dialog, "ইমেইল ঠিকানা").fill(email);
  await dialog.getByRole("button", { name: "কোড পাঠান" }).click();
  await expect(label(dialog, "৬ সংখ্যার কোড")).toBeVisible();
  await expectAccessible(page);

  await label(dialog, "৬ সংখ্যার কোড").fill(await readCode(page.request, email));
  await label(dialog, "বর্তমান পাসওয়ার্ড").fill("not-the-password");
  await dialog.getByRole("button", { name: "নিশ্চিত করুন" }).click();
  await expect(label(dialog, "বর্তমান পাসওয়ার্ড")).toHaveAttribute("aria-invalid", "true");

  await label(dialog, "বর্তমান পাসওয়ার্ড").fill(PASSWORD);
  await dialog.getByRole("button", { name: "নিশ্চিত করুন" }).click();

  await expect(dialog).toBeHidden();
  await expect(page.getByText(email)).toBeVisible();
  await expect(page.getByRole("button", { name: "বদলান: ইমেইল" })).toBeVisible();
});

test("changes the password, refusing a wrong current one", async ({ page }) => {
  const account = await signUpWithBusiness(page.request);
  await page.goto("/settings/security");

  await label(page, "বর্তমান পাসওয়ার্ড").fill("not-the-password");
  await label(page, "নতুন পাসওয়ার্ড").fill("a-new-password-2");
  await page.getByRole("button", { name: "পাসওয়ার্ড বদলান" }).click();
  await expect(label(page, "বর্তমান পাসওয়ার্ড")).toHaveAttribute("aria-invalid", "true");

  await label(page, "বর্তমান পাসওয়ার্ড").fill(PASSWORD);
  await page.getByRole("button", { name: "পাসওয়ার্ড বদলান" }).click();
  await expect(page.getByText("পাসওয়ার্ড বদলানো হয়েছে।")).toBeVisible();

  await signOut(page);
  await signIn(page, account.phone, "a-new-password-2");
  await expect(today(page)).toBeVisible();
});

test("turns on two-step sign-in, signs in with it, and turns it off", async ({ page }) => {
  const account = await signUpWithBusiness(page.request);
  await page.goto("/settings/security");

  // Turning it on: the password, the QR code, then the app's first code.
  await page.getByRole("button", { name: "চালু করুন" }).click();
  const dialog = page.getByRole("dialog");
  await label(dialog, "পাসওয়ার্ড").fill("not-the-password");
  await dialog.getByRole("button", { name: "এগিয়ে যান" }).click();
  await expect(label(dialog, "পাসওয়ার্ড")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "পাসওয়ার্ড").fill(PASSWORD);
  await dialog.getByRole("button", { name: "এগিয়ে যান" }).click();

  await expect(dialog.getByRole("img", { name: "অথেনটিকেটর অ্যাপের জন্য QR কোড" })).toBeVisible();
  await expectAccessible(page);
  const secret = (await page.getByTestId("two-factor-secret").textContent()) as string;

  await label(dialog, "অ্যাপের ৬ সংখ্যার কোড").fill(totp(secret) === "000000" ? "000001" : "000000");
  await dialog.getByRole("button", { name: "চালু করুন" }).click();
  await expect(label(dialog, "অ্যাপের ৬ সংখ্যার কোড")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "অ্যাপের ৬ সংখ্যার কোড").fill(totp(secret, -1));
  await dialog.getByRole("button", { name: "চালু করুন" }).click();

  // The recovery codes, shown once; the dialog stays until they are saved.
  const codes = page.getByTestId("recovery-codes").getByRole("listitem");
  await expect(codes).toHaveCount(10);
  const recoveryCode = (await codes.first().textContent()) as string;
  await page.keyboard.press("Escape");
  await expect(codes).toHaveCount(10);
  await expectAccessible(page);
  await page.getByRole("button", { name: "আমি রেখে দিয়েছি" }).click();
  await expect(page.getByText("রিকভারি কোড বাকি আছে: ১০টি")).toBeVisible();

  // Signing in now asks for the second step: a wrong code, then the app's.
  await signOut(page);
  await signIn(page, account.phone, PASSWORD);
  await expect(page.getByRole("heading", { level: 1, name: "দুই ধাপের লগ ইন" })).toBeVisible();
  await expectAccessible(page);
  const second = label(page, "অ্যাপের কোড বা রিকভারি কোড");
  await second.fill(totp(secret) === "000000" ? "000001" : "000000");
  await page.getByRole("button", { name: "নিশ্চিত করুন" }).click();
  await expect(second).toHaveAttribute("aria-invalid", "true");
  await second.fill(totp(secret));
  await page.getByRole("button", { name: "নিশ্চিত করুন" }).click();
  await expect(today(page)).toBeVisible();

  // A recovery code works too, once.
  await signOut(page);
  await signIn(page, account.phone, PASSWORD);
  await second.fill(recoveryCode);
  await page.getByRole("button", { name: "নিশ্চিত করুন" }).click();
  await expect(today(page)).toBeVisible();

  // Turning it off takes the password and a code; then signing in needs only the password.
  await page.goto("/settings/security");
  await expect(page.getByText("রিকভারি কোড বাকি আছে: ৯টি")).toBeVisible();
  await page.getByRole("button", { name: "বন্ধ করুন" }).click();
  const off = page.getByRole("dialog");
  await label(off, "পাসওয়ার্ড").fill(PASSWORD);
  await label(off, "অ্যাপের কোড বা রিকভারি কোড").fill(totp(secret, 1));
  await off.getByRole("button", { name: "বন্ধ করুন", exact: true }).first().click();
  await expect(page.getByText("দুই ধাপের লগ ইন বন্ধ করা হয়েছে।")).toBeVisible();

  await signOut(page);
  await signIn(page, account.phone, PASSWORD);
  await expect(today(page)).toBeVisible();
});

test("makes new recovery codes with a code from the app", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/settings/security");
  await page.getByRole("button", { name: "চালু করুন" }).click();
  const dialog = page.getByRole("dialog");
  await label(dialog, "পাসওয়ার্ড").fill(PASSWORD);
  await dialog.getByRole("button", { name: "এগিয়ে যান" }).click();
  const secret = (await page.getByTestId("two-factor-secret").textContent()) as string;
  await label(dialog, "অ্যাপের ৬ সংখ্যার কোড").fill(totp(secret, -1));
  await dialog.getByRole("button", { name: "চালু করুন" }).click();
  const codes = page.getByTestId("recovery-codes").getByRole("listitem");
  const first = (await codes.first().textContent()) as string;
  await page.getByRole("button", { name: "আমি রেখে দিয়েছি" }).click();

  await page.getByRole("button", { name: "নতুন রিকভারি কোড" }).click();
  await label(page.getByRole("dialog"), "অ্যাপের ৬ সংখ্যার কোড").fill(totp(secret));
  await page.getByRole("button", { name: "নতুন কোড তৈরি করুন" }).click();

  await expect(codes).toHaveCount(10);
  await expect(codes.first()).not.toHaveText(first);
});
