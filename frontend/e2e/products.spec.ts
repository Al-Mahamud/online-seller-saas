import type { APIRequestContext, Browser, Page } from "@playwright/test";
import { newEmail, PASSWORD, readCode, readInviteToken, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { png } from "./support/images";
import { expect, test } from "./support/test";

const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });
const heading = (page: Page, name: string) => page.getByRole("heading", { level: 1, name });

/** Adds an option through the form: its name, then each value with the keyboard. */
async function addOption(page: Page, number: number, name: string, values: string[]) {
  await page.getByRole("button", { name: "অপশন যোগ করুন" }).click();
  const group = page.getByRole("group", { name: `অপশন ${number}` });
  await label(group, "অপশনের নাম").fill(name);
  for (const value of values) {
    await label(group, "মান যোগ করুন").fill(value);
    await label(group, "মান যোগ করুন").press("Enter");
  }
}

async function xsrf(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  return (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

/** A product made through the API, for tests about something else. */
async function makeProduct(request: APIRequestContext, data: Record<string, unknown>): Promise<string> {
  const created = await request.post("/api/v1/products", { headers: { "X-XSRF-TOKEN": await xsrf(request) }, data });
  expect(created.status()).toBe(201);
  return ((await created.json()) as { id: string }).id;
}

test("creates a product with 2 options and 6 variants, from the list", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/products");
  await expect(page.getByText("এখনো কোনো পণ্য নেই।")).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("link", { name: "নতুন পণ্য" }).click();
  await expect(heading(page, "নতুন পণ্য")).toBeVisible();

  // The form says what's missing first.
  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();
  await expect(page.getByText("এটি পূরণ করুন।")).toBeVisible();
  await expect(label(page, "বিক্রয় দাম (৳)")).toHaveAttribute("aria-invalid", "true");

  await label(page, "পণ্যের নাম").fill("সুতি কুর্তি");
  await addOption(page, 1, "সাইজ", ["S", "M", "L"]);
  await addOption(page, 2, "রং", ["লাল"]);
  await label(page.getByRole("group", { name: "অপশন 2" }), "মান যোগ করুন").fill("নীল, ");
  await expect(page.getByRole("status").filter({ hasText: "মোট ৬টি ভ্যারিয়েন্ট" })).toBeVisible();
  await expectAccessible(page);

  await label(page, "বিক্রয় দাম (৳)").fill("১,৫০০");
  await label(page, "কেনা দাম (৳, ঐচ্ছিক)").fill("900");
  await label(page, "শুরুর স্টক (ঐচ্ছিক)").fill("5");
  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();

  await expect(heading(page, "সুতি কুর্তি")).toBeVisible();
  await expect(page.getByRole("button", { name: /^ইতিহাস:/ })).toHaveCount(6);
  await expect(page.getByText("S / লাল", { exact: true })).toBeVisible();
  await expect(page.getByText("L / নীল", { exact: true })).toBeVisible();
  await expect(page.getByText("৳১,৫০০").first()).toBeVisible();
  await expect(page.getByText("লাভ ৳৬০০").first()).toBeVisible();
  await expectAccessible(page);

  // And in the list.
  await page.getByRole("link", { name: "পণ্য", exact: true }).last().click();
  await expect(page.getByRole("link", { name: /সুতি কুর্তি/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /৬টি ভ্যারিয়েন্ট/ })).toBeVisible();
});

test("receives stock, counts it, and shows the history", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const id = await makeProduct(page.request, { name: "জামদানি শাড়ি", price: 680000 });
  await page.goto(`/products/${id}`);
  await expect(page.getByText("হাতে ০ · আলাদা করা ০ · বিক্রয়যোগ্য ০")).toBeVisible();

  await page.getByRole("button", { name: "স্টক বদলান" }).click();
  const dialog = page.getByRole("dialog");
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(dialog.getByText("অন্তত একটি ঘরে সংখ্যা লিখুন।")).toBeVisible();
  await label(dialog, "জামদানি শাড়ি").fill("১০");
  await label(dialog, "কারণ (ঐচ্ছিক)").fill("ইসলামপুর থেকে এলো");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("হাতে ১০ · আলাদা করা ০ · বিক্রয়যোগ্য ১০")).toBeVisible();

  await page.getByRole("button", { name: "স্টক বদলান" }).click();
  await page.getByRole("radio", { name: "গুনে দেখেছি" }).check();
  await label(dialog, "জামদানি শাড়ি").fill("7");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("হাতে ৭ · আলাদা করা ০ · বিক্রয়যোগ্য ৭")).toBeVisible();

  await page.getByRole("button", { name: /^ইতিহাস/ }).click();
  await expect(dialog.getByText("মাল এসেছে")).toBeVisible();
  await expect(dialog.getByText("ইসলামপুর থেকে এলো")).toBeVisible();
  await expect(dialog.getByText("গণনায় ঠিক করা")).toBeVisible();
  await expect(dialog.getByText("−৩")).toBeVisible();
  await expectAccessible(page);
});

test("refuses taking more stock than there is, with the server's message", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const id = await makeProduct(page.request, { name: "ব্যাগ", price: 10000, openingStock: 2 });
  await page.goto(`/products/${id}`);

  await page.getByRole("button", { name: "স্টক বদলান" }).click();
  const dialog = page.getByRole("dialog");
  await page.getByRole("radio", { name: "নষ্ট বা হারিয়ে গেছে" }).check();
  await label(dialog, "ব্যাগ").fill("5");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();

  await expect(dialog.getByRole("alert")).toBeVisible();
  await expect(dialog).toBeVisible();
});

test("edits a variant, its options, and archives and restores", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const id = await makeProduct(page.request, {
    name: "পাঞ্জাবি",
    price: 220000,
    costPrice: 150000,
    options: [{ name: "সাইজ", values: ["M", "L"] }],
  });
  await page.goto(`/products/${id}`);

  await page.getByRole("button", { name: "বদলান: L", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await expect(label(dialog, "বিক্রয় দাম (৳)")).toHaveValue("2200");
  await expect(label(dialog, "কেনা দাম (৳, ঐচ্ছিক)")).toHaveValue("1500");
  await expectAccessible(page);
  await label(dialog, "বিক্রয় দাম (৳)").fill("not money");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(label(dialog, "বিক্রয় দাম (৳)")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "বিক্রয় দাম (৳)").fill("2450.50");
  await label(dialog, "কম স্টকের সীমা (ঐচ্ছিক)").fill("3");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("৳২,৪৫০.৫০")).toBeVisible();

  // A new value adds a variant, keeping the others as they are.
  const group = page.getByRole("group", { name: "অপশন 1" });
  await label(group, "মান যোগ করুন").fill("XL");
  await label(group, "মান যোগ করুন").press("Enter");
  await page.getByRole("button", { name: "সংরক্ষণ করুন" }).nth(1).click();
  await expect(page.getByRole("button", { name: /^ইতিহাস:/ })).toHaveCount(3);
  await expect(page.getByText("৳২,৪৫০.৫০")).toBeVisible();

  await page.getByRole("button", { name: "আর্কাইভ করুন" }).click();
  await expect(page.getByRole("button", { name: "আবার চালু করুন" })).toBeVisible();
  await page.goto("/products");
  await expect(page.getByText("এখনো কোনো পণ্য নেই।")).toBeVisible();
  await page.getByRole("button", { name: "আর্কাইভ" }).click();
  await expect(page.getByRole("link", { name: /পাঞ্জাবি/ })).toBeVisible();
});

test("adds, arranges and removes images", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const id = await makeProduct(page.request, { name: "ছবির পণ্য", price: 10000 });
  await page.goto(`/products/${id}`);

  await label(page, "ছবি যোগ করুন").setInputFiles([
    { name: "one.png", mimeType: "image/png", buffer: png(200, 200) },
    { name: "two.png", mimeType: "image/png", buffer: png(300, 300) },
  ]);
  await expect(page.getByText("২টি, সর্বোচ্চ ৮টি")).toBeVisible();
  await expect(page.getByText("প্রধান", { exact: true })).toHaveCount(1);
  await expectAccessible(page);

  await page.getByRole("button", { name: "ছবি ২ খুলুন" }).click();
  await expectAccessible(page);
  await page.getByRole("button", { name: "প্রধান ছবি করুন" }).click();
  await expect(page.getByRole("button", { name: "প্রধান ছবি করুন" })).toBeHidden();
  await page.getByRole("button", { name: "ছবি সরান" }).click();
  await expect(page.getByText("১টি, সর্বোচ্চ ৮টি")).toBeVisible();

  // The list shows the main image.
  await page.goto("/products");
  await expect(page.locator("a img").filter({ visible: true })).toHaveCount(1);
});

test("searches and filters, keeping them in the address", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const headers = { "X-XSRF-TOKEN": await xsrf(page.request) };
  const women = await page.request.post("/api/v1/categories", { headers, data: { name: "মহিলা" } });
  const kurti = await page.request.post("/api/v1/categories", {
    headers,
    data: { name: "কুর্তি", parentId: ((await women.json()) as { id: string }).id },
  });
  const eid = await page.request.post("/api/v1/tags", { headers, data: { name: "ঈদ" } });
  await makeProduct(page.request, {
    name: "সুতি কুর্তি",
    price: 100000,
    categoryId: ((await kurti.json()) as { id: string }).id,
  });
  await makeProduct(page.request, {
    name: "পাঞ্জাবি",
    price: 200000,
    tagIds: [((await eid.json()) as { id: string }).id],
  });
  await page.goto("/products");
  const kurtiLink = page.getByRole("link", { name: /সুতি কুর্তি/ });
  const panjabiLink = page.getByRole("link", { name: /পাঞ্জাবি/ });
  await expect(kurtiLink).toBeVisible();
  await expect(panjabiLink).toBeVisible();

  await page.getByRole("button", { name: "মহিলা" }).click();
  await expect(panjabiLink).toBeHidden();
  await expect(kurtiLink).toBeVisible();
  await page.getByRole("button", { name: "কুর্তি", exact: true }).click();
  await expect(kurtiLink).toBeVisible();

  await page.getByRole("button", { name: "#ঈদ" }).click();
  await expect(kurtiLink).toBeHidden();
  await expect(panjabiLink).toBeVisible();
  await expect(page).toHaveURL(/tagId=/);
  await page.reload();
  await expect(panjabiLink).toBeVisible();
  await expect(kurtiLink).toBeHidden();

  await page.getByRole("button", { name: "সব", exact: true }).click();
  await page.getByRole("searchbox", { name: "পণ্য খুঁজুন" }).fill("কুর্তি");
  await expect(panjabiLink).toBeHidden();
  await expect(kurtiLink).toBeVisible();
  await expect(page).toHaveURL(/q=/);

  await page.getByRole("searchbox", { name: "পণ্য খুঁজুন" }).fill("কিছুই নেই এমন");
  await expect(page.getByText("এই খোঁজে কোনো পণ্য পাওয়া যায়নি।")).toBeVisible();
});

test("the low-stock list shows what's running low, and links to it", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await makeProduct(page.request, { name: "শেষের পথে", price: 10000, openingStock: 1 });
  await makeProduct(page.request, { name: "অনেক আছে", price: 10000, openingStock: 500 });
  await page.goto("/products");

  await expect(page.getByRole("link", { name: /শেষের পথে/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /অনেক আছে/ })).toBeVisible();
  await expect(page.getByText("স্টক কম", { exact: true }).filter({ visible: true })).toHaveCount(1);
  await page.getByRole("link", { name: "স্টক কম: ১টি" }).click();

  await expect(heading(page, "যে পণ্যের স্টক কম")).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("link", { name: /শেষের পথে/ }).click();
  await expect(heading(page, "শেষের পথে")).toBeVisible();
});

test("manages categories and tags", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/more");
  await page.getByRole("link", { name: "ক্যাটাগরি ও ট্যাগ" }).click();
  await expect(heading(page, "ক্যাটাগরি ও ট্যাগ")).toBeVisible();

  await label(page, "নতুন ক্যাটাগরি").fill("মহিলা");
  await page.getByRole("button", { name: "যোগ করুন" }).first().click();
  await expect(page.getByText("মহিলা", { exact: true })).toBeVisible();
  await label(page, "নতুন ক্যাটাগরি").fill("কুর্তি");
  await page.getByRole("button", { name: "যোগ করুন" }).first().click();
  await expect(page.getByText("কুর্তি", { exact: true })).toBeVisible();
  await expectAccessible(page);

  // Under the other one, then renamed.
  await page.getByRole("button", { name: "বদলান: কুর্তি" }).click();
  const dialog = page.getByRole("dialog");
  await label(dialog, "কোথায় থাকবে").selectOption({ label: "মহিলা" });
  await label(dialog, "নাম").fill("সুতি কুর্তি");
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("সুতি কুর্তি", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "মুছুন: সুতি কুর্তি" })).toBeVisible();

  // A name already used is refused, under the box.
  await label(page, "নতুন ক্যাটাগরি").fill("মহিলা");
  await page.getByRole("button", { name: "যোগ করুন" }).first().click();
  await expect(label(page, "নতুন ক্যাটাগরি")).toHaveAttribute("aria-invalid", "true");

  await page.getByRole("button", { name: "মুছুন: সুতি কুর্তি" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "মুছুন" }).click();
  await expect(page.getByText("সুতি কুর্তি", { exact: true })).toBeHidden();

  await label(page, "নতুন ট্যাগ").fill("ঈদ");
  await page.getByRole("button", { name: "যোগ করুন" }).last().click();
  await expect(page.getByText("ঈদ", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "মুছুন: ঈদ" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "মুছুন" }).click();
  await expect(page.getByText("ঈদ", { exact: true })).toBeHidden();
});

test("the plan's variant limit opens the upgrade dialog", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const business = (await (await page.request.get("/api/v1/business")).json()) as { id: string };
  const lowered = await page.request.post(`/dev/plan?businessId=${business.id}&code=STARTER`);
  expect(lowered.status()).toBe(204);

  // Starter allows 500 variants; a product can have 100.
  const hundred = [
    { name: "সাইজ", values: Array.from({ length: 10 }, (_, i) => `S${i}`) },
    { name: "রং", values: Array.from({ length: 10 }, (_, i) => `C${i}`) },
  ];
  for (let i = 0; i < 5; i++) {
    await makeProduct(page.request, { name: `বড় পণ্য ${i}`, price: 10000, options: hundred });
  }

  await page.goto("/products/new");
  await label(page, "পণ্যের নাম").fill("একটি বেশি");
  await label(page, "বিক্রয় দাম (৳)").fill("100");
  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "আপনার প্ল্যানের সীমা শেষ" })).toBeVisible();
  await expect(dialog).toContainText("ভ্যারিয়েন্ট");
  await expect(dialog).toContainText("৫০০");
  await expectAccessible(page);
  await dialog.getByRole("link", { name: "প্ল্যান দেখুন" }).click();
  await expect(page).toHaveURL(/\/billing$/);
});

async function joinAsStaff(owner: Page, browser: Browser): Promise<Page> {
  const email = newEmail();
  const headers = { "X-XSRF-TOKEN": await xsrf(owner.request) };
  const sent = await owner.request.post("/api/v1/staff/invites", { headers, data: { to: email, role: "STAFF" } });
  expect(sent.status()).toBe(201);
  const token = await readInviteToken(owner.request, email);

  const context = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const staff = await context.newPage();
  const staffHeaders = { "X-XSRF-TOKEN": await xsrf(staff.request), "Accept-Language": "bn" };
  await staff.request.post("/api/v1/auth/otp", { headers: staffHeaders, data: { to: email, purpose: "SIGNUP" } });
  const code = await readCode(owner.request, email);
  await staff.request.post("/api/v1/auth/signup", {
    headers: staffHeaders,
    data: {
      name: "Sumi Staff",
      phone: `018${String(Math.floor(Math.random() * 1e8)).padStart(8, "0")}`,
      email,
      password: PASSWORD,
      codeSentTo: "EMAIL",
      code,
    },
  });
  const joined = await staff.request.post("/api/v1/invites/accept", { headers: staffHeaders, data: { token } });
  expect(joined.status()).toBe(200);
  return staff;
}

test("staff see no cost anywhere, can't edit, and can change stock", async ({ page, browser }) => {
  await signUpWithBusiness(page.request);
  const id = await makeProduct(page.request, { name: "গোপন দামের পণ্য", price: 200000, costPrice: 123400 });
  const staff = await joinAsStaff(page, browser);

  // Nothing about cost in what the server sends.
  const answer = await staff.request.get(`/api/v1/products/${id}`);
  expect(await answer.text()).not.toMatch(/costPrice|margin|123400/);

  await staff.goto(`/products/${id}`);
  await expect(heading(staff, "গোপন দামের পণ্য")).toBeVisible();
  await expect(staff.getByText("৳২,০০০")).toBeVisible();
  await expect(staff.getByText(/কেনা|লাভ|১,২৩৪/)).toBeHidden();
  await expect(staff.getByRole("button", { name: /^বদলান/ })).toBeHidden();
  await expect(staff.getByRole("button", { name: "আর্কাইভ করুন" })).toBeHidden();
  await expect(staff.getByRole("textbox", { name: "পণ্যের নাম" })).toBeHidden();

  await staff.getByRole("button", { name: "স্টক বদলান" }).click();
  await label(staff.getByRole("dialog"), "গোপন দামের পণ্য").fill("3");
  await staff.getByRole("dialog").getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(staff.getByText("হাতে ৩ · আলাদা করা ০ · বিক্রয়যোগ্য ৩")).toBeVisible();

  await staff.goto("/products/new");
  await expect(staff).toHaveURL(/\/more$/);
  await staff.goto("/products");
  await expect(staff.getByRole("link", { name: "নতুন পণ্য" })).toBeHidden();
  await staff.context().close();
});
