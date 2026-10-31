import type { Page } from "@playwright/test";
import { lastCode, newEmail, newPhone, newUserId, PASSWORD, readCode, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const label = (page: Page, name: string) => page.getByLabel(name, { exact: true });

/** Someone new lands on creating their first business. */
async function expectFirstRun(page: Page) {
  await expect(page.getByRole("heading", { level: 1, name: "আপনার ব্যবসা যোগ করুন" })).toBeVisible();
}

async function expectToday(page: Page) {
  await expect(page.getByRole("navigation", { name: "প্রধান" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1, name: "আজ" })).toBeVisible();
}

/** From the first-run page, which has no tabs. */
async function signOutFromFirstRun(page: Page) {
  await page.getByRole("button", { name: "লগ আউট" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "আবার স্বাগতম" })).toBeVisible();
}

async function signInWithPassword(page: Page, userId: string, password: string) {
  await label(page, "ইউজার আইডি").fill(userId);
  await label(page, "পাসওয়ার্ড").fill(password);
  await page.getByRole("button", { name: "লগ ইন", exact: true }).click();
}

test("signs up with a code by SMS, stays signed in, signs out", async ({ page }) => {
  const phone = newPhone();
  await page.goto("/sign-up");

  await label(page, "আপনার নাম").fill("Rahima Khatun");
  // Bangla digits, as a Bangla keyboard types them.
  await label(page, "ফোন নম্বর").fill(phone.replace(/\d/g, (digit) => "০১২৩৪৫৬৭৮৯"[Number(digit)] as string));
  await label(page, "ইউজার আইডি").fill(newUserId());
  await label(page, "পাসওয়ার্ড").fill(PASSWORD);
  await label(page, "পাসওয়ার্ড আবার দিন").fill(PASSWORD);
  await page.getByRole("button", { name: "এগিয়ে যান" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "কোড দিন" })).toBeVisible();
  await expectAccessible(page);
  await label(page, "৬ সংখ্যার কোড").fill(await readCode(page.request, phone));
  await page.getByRole("button", { name: "অ্যাকাউন্ট খুলুন" }).click();

  await expectFirstRun(page);
  await page.reload();
  await expectFirstRun(page);

  await signOutFromFirstRun(page);
  await page.goto("/products");
  await expect(page).toHaveURL(/\/sign-in/);
});

test("signs up with a code by email and signs in with the User ID", async ({ page }) => {
  const email = newEmail();
  const userId = newUserId();
  await page.goto("/sign-up");

  await label(page, "আপনার নাম").fill("Rahima Khatun");
  await label(page, "ফোন নম্বর").fill(newPhone());
  await label(page, "ইউজার আইডি").fill(userId);
  await label(page, "ইমেইল (ঐচ্ছিক)").fill(email);
  await label(page, "পাসওয়ার্ড").fill(PASSWORD);
  await label(page, "পাসওয়ার্ড আবার দিন").fill(PASSWORD);
  await page.getByRole("radio", { name: "ইমেইল" }).check();
  await page.getByRole("button", { name: "এগিয়ে যান" }).click();

  await label(page, "৬ সংখ্যার কোড").fill(await readCode(page.request, email));
  await page.getByRole("button", { name: "অ্যাকাউন্ট খুলুন" }).click();
  await expectFirstRun(page);

  await signOutFromFirstRun(page);
  await signInWithPassword(page, userId, PASSWORD);
  await expectFirstRun(page);
});

test("checks the sign-up form before sending anything", async ({ page }) => {
  await page.goto("/sign-up");

  await page.getByRole("button", { name: "এগিয়ে যান" }).click();

  await expect(page.getByText("এটি পূরণ করুন।").first()).toBeVisible();
  await expect(page.getByText("পাসওয়ার্ড কমপক্ষে ৮ অক্ষরের হতে হবে।")).toBeVisible();
  await expectAccessible(page);

  // The message follows the language, without sending the form again.
  await page.getByRole("button", { name: "English" }).click();
  await expect(page.getByText("The password must be at least 8 characters.")).toBeVisible();
});

test("the two passwords must match before a code is sent", async ({ page }) => {
  const phone = newPhone();
  await page.goto("/sign-up");

  await label(page, "আপনার নাম").fill("Rahima Khatun");
  await label(page, "ফোন নম্বর").fill(phone);
  await label(page, "ইউজার আইডি").fill(newUserId());
  await label(page, "পাসওয়ার্ড").fill(PASSWORD);
  await label(page, "পাসওয়ার্ড আবার দিন").fill(`${PASSWORD}x`);
  await page.getByRole("button", { name: "এগিয়ে যান" }).click();

  await expect(page.getByText("পাসওয়ার্ড দুটি মেলেনি।")).toBeVisible();
  await expect(label(page, "পাসওয়ার্ড আবার দিন")).toHaveAttribute("aria-invalid", "true");
  await expectAccessible(page);
  expect(await lastCode(page.request, phone)).toBeNull();

  await label(page, "পাসওয়ার্ড আবার দিন").fill(PASSWORD);
  await page.getByRole("button", { name: "এগিয়ে যান" }).click();
  await label(page, "৬ সংখ্যার কোড").fill(await readCode(page.request, phone));
  await page.getByRole("button", { name: "অ্যাকাউন্ট খুলুন" }).click();
  await expectFirstRun(page);
});

test("a detail the server refuses leads back to the details, with the problem under its field", async ({ page }) => {
  const phone = newPhone();
  await page.goto("/sign-up");

  await label(page, "আপনার নাম").fill("Rahima Khatun");
  await label(page, "ফোন নম্বর").fill(phone);
  await label(page, "ইউজার আইডি").fill(newUserId());
  await label(page, "ইমেইল (ঐচ্ছিক)").fill("not-an-email");
  await label(page, "পাসওয়ার্ড").fill(PASSWORD);
  await label(page, "পাসওয়ার্ড আবার দিন").fill(PASSWORD);
  await page.getByRole("button", { name: "এগিয়ে যান" }).click();
  await label(page, "৬ সংখ্যার কোড").fill(await readCode(page.request, phone));
  await page.getByRole("button", { name: "অ্যাকাউন্ট খুলুন" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "নতুন অ্যাকাউন্ট" })).toBeVisible();
  await expect(label(page, "ইমেইল (ঐচ্ছিক)")).toHaveAttribute("aria-invalid", "true");
});

async function xsrf(page: Page): Promise<string> {
  const cookies = await page.context().cookies();
  return cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

/** An account in a browser that has signed out again. */
async function signedOutAccount(page: Page) {
  const account = await signUpWithBusiness(page.request);
  await page.request.post("/api/v1/auth/logout", { headers: { "X-XSRF-TOKEN": await xsrf(page) } });
  return account;
}

test("signs in with a password and returns to the page asked for", async ({ page }) => {
  const account = await signedOutAccount(page);
  await page.goto("/products");
  await expect(page).toHaveURL(/redirect=/);

  await signInWithPassword(page, account.userId, account.password);

  await expect(page).toHaveURL(/\/products$/);
  await expect(page.getByRole("heading", { level: 1, name: "পণ্য" })).toBeVisible();
});

test("a wrong password shows the server's message, in the language on screen", async ({ page }) => {
  const account = await signedOutAccount(page);
  await page.goto("/sign-in");

  await signInWithPassword(page, account.userId, "not-the-password");
  const bangla = page.getByRole("alert").filter({ hasNotText: /[A-Za-z]{4,}/ });
  await expect(bangla).toBeVisible();

  await page.getByRole("button", { name: "English" }).click();
  await label(page, "Password").fill("still-not-the-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText(/[A-Za-z]{4,}/);
  await expect(page).toHaveURL(/\/sign-in/);
});

test("offers no way to sign in except the User ID and password", async ({ page }) => {
  const account = await signedOutAccount(page);
  await page.goto("/sign-in");

  await expect(page.getByRole("button", { name: "কোড দিয়ে লগ ইন" })).toHaveCount(0);

  await signInWithPassword(page, account.phone, account.password);
  await expect(page.getByRole("alert")).toContainText("ইউজার আইডি বা পাসওয়ার্ড সঠিক নয়।");
  await expect(label(page, "পাসওয়ার্ড")).toHaveValue("");
  await expect(label(page, "ইউজার আইডি")).toHaveValue(account.phone);
});

test("takes the User ID with capitals, as a phone's keyboard may", async ({ page }) => {
  const account = await signedOutAccount(page);
  await page.goto("/sign-in");

  await signInWithPassword(page, account.userId.toUpperCase(), account.password);

  await expectToday(page);
});

test("recovers a forgotten User ID with a code, and fills it in", async ({ page }) => {
  const account = await signedOutAccount(page);
  const earlier = await lastCode(page.request, account.phone);
  await page.goto("/sign-in");

  await page.getByRole("link", { name: "ইউজার আইডি ভুলে গেছেন?" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "ইউজার আইডি ভুলে গেছেন?" })).toBeVisible();
  await expectAccessible(page);
  await label(page, "ফোন নম্বর বা ইমেইল").fill(account.phone);
  await page.getByRole("button", { name: "কোড পাঠান" }).click();

  const code = await readCode(page.request, account.phone, earlier);
  const wrong = code === "000000" ? "000001" : "000000";
  await label(page, "৬ সংখ্যার কোড").fill(wrong);
  await page.getByRole("button", { name: "নিশ্চিত করুন" }).click();
  await expect(label(page, "৬ সংখ্যার কোড")).toHaveAttribute("aria-invalid", "true");
  await expectAccessible(page);

  await label(page, "৬ সংখ্যার কোড").fill(code);
  await page.getByRole("button", { name: "নিশ্চিত করুন" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "আপনার ইউজার আইডি" })).toBeVisible();
  await expect(page.getByText(account.userId, { exact: true })).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("link", { name: "লগ ইন করুন" }).click();
  await expect(label(page, "ইউজার আইডি")).toHaveValue(account.userId);
});

test("the code boxes take a pasted code with spaces and Bangla digits", async ({ page }) => {
  const account = await signedOutAccount(page);
  await page.goto("/forgot-user-id");
  await label(page, "ফোন নম্বর বা ইমেইল").fill(account.phone);
  await page.getByRole("button", { name: "কোড পাঠান" }).click();
  await expect(label(page, "৬ সংখ্যার কোড")).toBeVisible();

  await label(page, "৬ সংখ্যার কোড").fill("১২৩ 456 789");

  await expect(label(page, "৬ সংখ্যার কোড")).toHaveValue("১২৩456");
});

test("checks a User ID: free, taken, and against the rules", async ({ page }) => {
  const existing = await signedOutAccount(page);
  await page.goto("/sign-up");
  const box = label(page, "ইউজার আইডি");
  const check = page.getByRole("button", { name: "যাচাই করুন" });

  await box.fill("my shop!");
  await check.click();
  await expect(page.getByText("৪–২০ অক্ষর দিন: শুধু ইংরেজি ছোট হাতের অক্ষর, সংখ্যা আর _")).toBeVisible();
  await expect(box).toHaveAttribute("aria-invalid", "true");

  await box.fill(existing.userId);
  await expect(box).not.toHaveAttribute("aria-invalid", "true");
  await check.click();
  await expect(page.getByText("এই ইউজার আইডিটি আগেই নেওয়া হয়েছে। অন্যটি দিন।")).toBeVisible();
  await expectAccessible(page);

  await box.fill(newUserId().toUpperCase());
  await expect(box).toHaveValue(/^[a-z0-9_]+$/);
  await check.click();
  await expect(page.getByText("এই ইউজার আইডিটি নেওয়া যাবে।").first()).toBeVisible();
  await expectAccessible(page);

  // Typing again means the answer no longer stands.
  await box.fill(`${newUserId()}x`.slice(0, 20));
  await expect(page.getByText("এই ইউজার আইডিটি নেওয়া যাবে।")).toHaveCount(0);
});

test("going on with a taken User ID stops before a code is sent", async ({ page }) => {
  const existing = await signedOutAccount(page);
  const phone = newPhone();
  await page.goto("/sign-up");

  await label(page, "আপনার নাম").fill("Rahima Khatun");
  await label(page, "ফোন নম্বর").fill(phone);
  await label(page, "ইউজার আইডি").fill(existing.userId);
  await label(page, "পাসওয়ার্ড").fill(PASSWORD);
  await label(page, "পাসওয়ার্ড আবার দিন").fill(PASSWORD);
  await page.getByRole("button", { name: "এগিয়ে যান" }).click();

  await expect(page.getByText("এই ইউজার আইডিটি আগেই নেওয়া হয়েছে। অন্যটি দিন।")).toBeVisible();
  expect(await lastCode(page.request, phone)).toBeNull();
});

test("sets a new password with a code", async ({ page }) => {
  const account = await signedOutAccount(page);
  const earlier = await lastCode(page.request, account.phone);
  await page.goto("/sign-in");

  await page.getByRole("link", { name: "পাসওয়ার্ড ভুলে গেছেন?" }).click();
  // The sign-in page has a box with this label too: wait for the new page, or the ID goes in the old one.
  await expect(page.getByRole("heading", { level: 1, name: "পাসওয়ার্ড ভুলে গেছেন?" })).toBeVisible();
  await label(page, "ইউজার আইডি").fill(account.userId);
  await page.getByRole("button", { name: "কোড পাঠান" }).click();
  await label(page, "৬ সংখ্যার কোড").fill(await readCode(page.request, account.phone, earlier));
  await label(page, "নতুন পাসওয়ার্ড").fill("a-new-password-2");
  await expectAccessible(page);
  await page.getByRole("button", { name: "পাসওয়ার্ড বদলান" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "আবার স্বাগতম" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "পাসওয়ার্ড বদলানো হয়েছে" })).toBeVisible();
  await expect(label(page, "ইউজার আইডি")).toHaveValue(account.userId);
  await signInWithPassword(page, account.userId, "a-new-password-2");
  await expectToday(page);
});

test("someone signed in does not see the sign-in page", async ({ page }) => {
  await signUpWithBusiness(page.request);

  await page.goto("/sign-in");

  await expectToday(page);
});

test("when the session ends, the next request leads to sign-in", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/more");
  await expect(page.getByRole("button", { name: "লগ আউট" })).toBeVisible();

  // The session goes away behind the app's back, as when it expires.
  await page.context().clearCookies({ name: "SESSION" });
  await page.getByRole("button", { name: "English" }).click();

  await expect(page).toHaveURL(/\/sign-in\?redirect=%2Fmore$/);
});
