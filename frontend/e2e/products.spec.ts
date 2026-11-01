import type { APIRequestContext, Page } from "@playwright/test";
import { joinAsStaff, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { png } from "./support/images";
import { expect, test } from "./support/test";

const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });
const heading = (page: Page, name: string) => page.getByRole("heading", { level: 1, name });

/** One variant's boxes in the new-product form, by its number on screen. */
const box = (page: Page, number: string, name: string) => label(page, `ভ্যারিয়েন্ট ${number}: ${name}`);

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

test("creates a product whose variants each have their own size, quantity and prices", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/products");
  await expect(page.getByText("এখনো কোনো পণ্য নেই।")).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("link", { name: "নতুন পণ্য" }).click();
  await expect(heading(page, "নতুন পণ্য")).toBeVisible();

  // The form says what's missing first.
  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();
  await expect(page.getByText("এটি পূরণ করুন।")).toBeVisible();
  await expect(page.getByText("বিক্রয় দাম দিন।")).toBeVisible();
  await expect(box(page, "১", "বিক্রয় দাম (৳)")).toHaveAttribute("aria-invalid", "true");
  await expectAccessible(page);

  await label(page, "পণ্যের নাম").fill("সুতি কুর্তি");
  await box(page, "১", "নাম").fill("লাল");
  await box(page, "১", "সাইজ").fill("M");
  await box(page, "১", "পরিমাণ").fill("১০");
  await box(page, "১", "কেনা দাম (৳)").fill("850");
  await box(page, "১", "বিক্রয় দাম (৳)").fill("১,৪০০");

  // A new row starts like the one above: the same name and prices, its own size and quantity.
  await page.getByRole("button", { name: "আরেকটি ভ্যারিয়েন্ট যোগ করুন" }).click();
  await expect(box(page, "২", "নাম")).toHaveValue("লাল");
  await expect(box(page, "২", "বিক্রয় দাম (৳)")).toHaveValue("১,৪০০");
  await expect(box(page, "২", "সাইজ")).toBeFocused();
  await box(page, "২", "সাইজ").fill("m");
  await expect(page.getByText("এই নাম আর সাইজের ভ্যারিয়েন্ট আগেই আছে।").first()).toBeVisible();
  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();
  await expect(page.getByText("পণ্য তৈরির আগে লাল চিহ্ন দেওয়া ঘরগুলো ঠিক করুন।")).toBeVisible();
  await box(page, "২", "সাইজ").fill("L");
  await box(page, "২", "পরিমাণ").fill("8");

  await page.getByRole("button", { name: "আরেকটি ভ্যারিয়েন্ট যোগ করুন" }).click();
  await box(page, "৩", "নাম").fill("নীল");
  await box(page, "৩", "সাইজ").fill("M");
  await box(page, "৩", "পরিমাণ").fill("12");
  await box(page, "৩", "কেনা দাম (৳)").fill("1600");
  // Below cost is a warning, not a refusal: a sale can do that on purpose.
  await expect(page.getByText("বিক্রয় দাম কেনা দামের চেয়ে কম।")).toBeVisible();
  await box(page, "৩", "কেনা দাম (৳)").fill("900");
  await box(page, "৩", "বিক্রয় দাম (৳)").fill("1500");
  await expect(page.getByRole("status").filter({ hasText: "৩টি ভ্যারিয়েন্ট, মোট ৩০টি" })).toBeVisible();

  // A row added by mistake goes again.
  await page.getByRole("button", { name: "আরেকটি ভ্যারিয়েন্ট যোগ করুন" }).click();
  await page.getByRole("button", { name: "ভ্যারিয়েন্ট ৪ মুছুন" }).click();
  await expect(box(page, "৪", "নাম")).toHaveCount(0);
  await expectAccessible(page);

  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();

  await expect(heading(page, "সুতি কুর্তি")).toBeVisible();
  await expect(page.getByRole("button", { name: /^ইতিহাস:/ })).toHaveCount(3);
  await expect(page.getByText("লাল / M", { exact: true })).toBeVisible();
  await expect(page.getByText("নীল / M", { exact: true })).toBeVisible();
  await expect(page.getByText("নীল / L", { exact: true })).toHaveCount(0);
  await expect(page.getByText("৳১,৫০০").first()).toBeVisible();
  await expect(page.getByText("হাতে ১২ · আলাদা করা ০ · বিক্রয়যোগ্য ১২")).toBeVisible();
  await expectAccessible(page);

  // And in the list.
  await page.getByRole("link", { name: "পণ্য", exact: true }).last().click();
  await expect(page.getByRole("link", { name: /সুতি কুর্তি/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /৩টি ভ্যারিয়েন্ট/ })).toBeVisible();
});

test("a product of one kind needs only a selling price", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/products/new");
  await label(page, "পণ্যের নাম").fill("হাতঘড়ি");
  await box(page, "১", "বিক্রয় দাম (৳)").fill("500");
  await expect(page.getByRole("button", { name: "ভ্যারিয়েন্ট ১ মুছুন" })).toHaveCount(0);
  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();
  await expect(heading(page, "হাতঘড়ি")).toBeVisible();
  await expect(page.getByRole("button", { name: /^ইতিহাস:/ })).toHaveCount(1);
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

test("edits a variant, adds another, and archives and restores", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const id = await makeProduct(page.request, {
    name: "পাঞ্জাবি",
    price: 220000,
    costPrice: 150000,
    variants: [
      { size: "M", price: 220000, costPrice: 150000 },
      { size: "L", price: 220000, costPrice: 150000 },
    ],
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

  // Its name and size can be changed too; the same as another's can't.
  await page.getByRole("button", { name: "বদলান: L", exact: true }).click();
  await expect(label(dialog, "সাইজ")).toHaveValue("L");
  await label(dialog, "সাইজ").fill("M");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(dialog.getByText("এই নাম আর সাইজের ভ্যারিয়েন্ট আগেই আছে।")).toBeVisible();
  await label(dialog, "সাইজ").fill("XL");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText("XL", { exact: true })).toBeVisible();

  // A new variant has its own prices and stock, and leaves the others as they are.
  await page.getByRole("button", { name: "ভ্যারিয়েন্ট যোগ করুন" }).click();
  const adding = page.getByRole("dialog");
  await expectAccessible(page);
  await label(adding, "নাম").fill("নীল");
  await label(adding, "সাইজ").fill("S");
  await label(adding, "পরিমাণ").fill("7");
  await label(adding, "কেনা দাম (৳)").fill("1000");
  await label(adding, "বিক্রয় দাম (৳)").fill("1800");
  await adding.getByRole("button", { name: "ভ্যারিয়েন্ট যোগ করুন" }).click();
  await expect(adding).toBeHidden();
  await expect(page.getByRole("button", { name: /^ইতিহাস:/ })).toHaveCount(3);
  await expect(page.getByText("নীল / S", { exact: true })).toBeVisible();
  await expect(page.getByText("৳১,৮০০")).toBeVisible();
  await expect(page.getByText("হাতে ৭ · আলাদা করা ০ · বিক্রয়যোগ্য ৭")).toBeVisible();
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
  await box(page, "১", "বিক্রয় দাম (৳)").fill("100");
  await page.getByRole("button", { name: "পণ্য তৈরি করুন" }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("heading", { name: "আপনার প্ল্যানের সীমা শেষ" })).toBeVisible();
  await expect(dialog).toContainText("ভ্যারিয়েন্ট");
  await expect(dialog).toContainText("৫০০");
  await expectAccessible(page);
  await dialog.getByRole("link", { name: "প্ল্যান দেখুন" }).click();
  await expect(page).toHaveURL(/\/billing$/);
});

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
