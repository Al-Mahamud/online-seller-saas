import type { APIRequestContext, Browser, Page } from "@playwright/test";
import { newPhone, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

type Scope = Page | ReturnType<Page["getByRole"]>;

const label = (scope: Scope, name: string) => scope.getByLabel(name, { exact: true });
const heading = (page: Page, name: string | RegExp) => page.getByRole("heading", { level: 1, name });
const toast = (page: Page, text: string) => page.getByText(text, { exact: true }).first();

/** An order's number as the app shows it in Bangla. */
const banglaNumber = (label: string) => label.replace(/\d/g, (digit) => "০১২৩৪৫৬৭৮৯"[Number(digit)] ?? digit);

async function xsrf(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  return (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

async function post(request: APIRequestContext, path: string, data?: unknown) {
  const answer = await request.post(path, { headers: { "X-XSRF-TOKEN": await xsrf(request) }, data });
  expect(answer.ok(), `${path}: ${await answer.text()}`).toBe(true);
  return (await answer.json()) as Record<string, never>;
}

/** A product with stock, through the API; the tests below are about orders. */
async function product(request: APIRequestContext, name: string, stock: number, price = 150000) {
  const created = await post(request, "/api/v1/products", { name, price, openingStock: stock });
  const [variant] = created.variants as unknown as { id: string }[];
  return { id: created.id as unknown as string, variantId: variant?.id as string };
}

/** A complete order through the API, confirmed unless asked otherwise. */
async function makeOrder(
  request: APIRequestContext,
  variantId: string,
  { confirmed = true, name = "রহিমা", phone = newPhone(), quantity = 1 } = {},
) {
  const order = await post(request, "/api/v1/orders", {
    customer: { name, phone, address: "বাসা ৪, রোড ২", districtId: 47 },
    items: [{ variantId, quantity }],
    deliveryCharge: 6000,
  });
  if (confirmed) {
    await post(request, `/api/v1/orders/${order.id}/confirm`);
  }
  return { id: order.id as unknown as string, label: banglaNumber(order.numberLabel as unknown as string), phone };
}

async function available(request: APIRequestContext, productId: string): Promise<number> {
  const answer = await request.get(`/api/v1/products/${productId}`);
  const body = (await answer.json()) as { variants: { available: number }[] };
  return body.variants[0]?.available ?? -1;
}

async function pick(page: Page, field: RegExp, search: string, name: string) {
  await page.getByRole("button", { name: field }).click();
  const list = page.getByRole("dialog").last();
  await label(list, "খুঁজুন").fill(search);
  await list.getByRole("button", { name, exact: true }).click();
}

async function addItem(page: Page, name: string) {
  await page.getByRole("searchbox", { name: "পণ্য খুঁজুন" }).fill(name);
  await page
    .getByRole("button", { name: new RegExp(name) })
    .first()
    .click();
  await expect(page.getByRole("group", { name })).toBeVisible();
}

test("takes a new order for a new customer through delivery with COD and a return", async ({ page }, info) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "সুতি কুর্তি", 5);
  const phone = newPhone();
  await page.goto("/orders");
  await expect(page.getByText("এখনো কোনো অর্ডার নেই।")).toBeVisible();
  await expectAccessible(page);

  // The quick order, timed from the moment the form opens. A person is slower than this, so the steps
  // are the point: nothing here needs more than a field or a tap. The seller sessions time real people.
  await page.getByRole("link", { name: "নতুন অর্ডার" }).click();
  await expect(heading(page, "নতুন অর্ডার")).toBeVisible();
  const started = Date.now();
  await label(page, "ফোন নম্বর").fill(phone);
  await label(page, "গ্রাহকের নাম").fill("রহিমা খাতুন");
  await label(page, "ঠিকানা").fill("বাসা ৪, রোড ২");
  await pick(page, /^জেলা/, "Dhaka", "ঢাকা");
  await addItem(page, "সুতি কুর্তি");
  await page.getByRole("button", { name: "একটি বাড়ান" }).click();
  await label(page, "অগ্রিম (৳)").fill("500");
  await expect(page.getByText("৳২,৫৭০")).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("button", { name: "সংরক্ষণ করে নিশ্চিত করুন" }).click();
  await expect(heading(page, /১০০১/)).toBeVisible();
  const seconds = (Date.now() - started) / 1000;
  info.annotations.push({ type: "quick order", description: `${seconds.toFixed(1)} s` });
  expect(seconds).toBeLessThan(60);

  await expect(toast(page, "অর্ডার নিশ্চিত হয়েছে।")).toBeVisible();
  await expect(page.getByRole("link", { name: "রহিমা খাতুন", exact: true })).toBeVisible();
  await expectAccessible(page);
  expect(await available(page.request, kurti.id)).toBe(3);

  await page.getByRole("button", { name: "প্যাক হয়েছে" }).click();
  await expect(toast(page, "অর্ডার প্যাক হয়েছে।")).toBeVisible();

  // Shipping: a courier from the list, and a tracking number.
  await page.getByRole("button", { name: "কুরিয়ারে দিন" }).click();
  const ship = page.getByRole("dialog");
  await ship.getByRole("button", { name: "কুরিয়ারে দিন" }).click();
  await expect(label(ship, "কুরিয়ার")).toHaveAttribute("aria-invalid", "true");
  await ship.getByRole("button", { name: "Pathao" }).click();
  await label(ship, "ট্র্যাকিং নম্বর (ঐচ্ছিক)").fill("PA-778");
  await expectAccessible(page);
  await ship.getByRole("button", { name: "কুরিয়ারে দিন" }).click();
  await expect(toast(page, "অর্ডার কুরিয়ারে গেছে।")).toBeVisible();
  await expect(page.getByText("আদায় করবে ৳২,৫৭০")).toBeVisible();

  // Delivered: the courier took what it was to collect.
  await page.getByRole("button", { name: "ডেলিভারি হয়েছে" }).click();
  const deliver = page.getByRole("dialog");
  await expect(label(deliver, "কুরিয়ার কত আদায় করেছে (৳)")).toHaveValue("2570");
  await deliver.getByRole("button", { name: "ডেলিভারি হয়েছে" }).click();
  await expect(toast(page, "ডেলিভারি লেখা হয়েছে।")).toBeVisible();
  await expect(page.getByText("আদায় করেছে ৳২,৫৭০")).toBeVisible();

  // One comes back: on the shelf again, with the money for it offered as a refund.
  await page.getByRole("button", { name: "পণ্য ফেরত নিন" }).click();
  const back = page.getByRole("dialog");
  await label(back, "কারণ").fill("সাইজ ছোট");
  await label(back.getByRole("group", { name: "সুতি কুর্তি" }), "কয়টি").fill("১");
  await expect(label(back, "গ্রাহককে ফেরত (৳)")).toHaveValue("1500");
  await expectAccessible(page);
  await back.getByRole("button", { name: "পণ্য ফেরত নিন" }).click();
  await expect(toast(page, "ফেরত লেখা হয়েছে।")).toBeVisible();
  await expect(page.getByText("সাইজ ছোট")).toBeVisible();
  await expect(page.getByText(/ফেরত দিয়েছেন ৳১,৫০০/)).toBeVisible();
  expect(await available(page.request, kurti.id)).toBe(4);

  // The history tells the whole story, by whom.
  const history = page
    .getByRole("region", { name: "অর্ডারের ইতিহাস" })
    .or(page.locator("section").filter({ hasText: "অর্ডারের ইতিহাস" }));
  await expect(history.getByText("Pathao PA-778")).toBeVisible();
  await expect(history.getByText("Test Seller").first()).toBeVisible();
});

test("a known customer fills in by any way of writing the number, and the charge follows the place", async ({
  page,
}) => {
  await signUpWithBusiness(page.request);
  await product(page.request, "জামদানি", 2, 680000);
  const phone = "01712345678";
  await post(page.request, "/api/v1/customers", {
    name: "নাসরিন",
    phone,
    address: "ফ্ল্যাট ৩বি",
    districtId: 47,
  });
  await page.goto("/orders/new");

  await label(page, "ফোন নম্বর").fill("০১৭১২-৩৪৫৬৭৮");
  await expect(page.getByRole("status").filter({ hasText: "নাসরিন" })).toBeVisible();
  await expect(label(page, "গ্রাহকের নাম")).toHaveCount(0);
  await expect(label(page, "ঠিকানা")).toHaveValue("ফ্ল্যাট ৩বি");
  await expect(page.getByRole("button", { name: /^জেলা/ })).toContainText("ঢাকা");
  await expect(label(page, "ডেলিভারি চার্জ (৳)")).toHaveValue("70");

  // Another place, another quote; then the seller's own number stands.
  await page.getByRole("button", { name: "সরান: জেলা" }).click();
  await pick(page, /^জেলা/, "Sylhet", "সিলেট");
  await expect(label(page, "ডেলিভারি চার্জ (৳)")).toHaveValue("130");
  await label(page, "ডেলিভারি চার্জ (৳)").fill("100");

  // Nothing to sell yet: it says so, right there.
  await page.getByRole("button", { name: "খসড়া রাখুন" }).click();
  await expect(page.getByText("অন্তত একটি পণ্য যোগ করুন।")).toBeVisible();

  await addItem(page, "জামদানি");
  await label(page, "ছাড় (৳)").fill("৮০");
  await expect(page.getByText("৳৬,৮২০").first()).toBeVisible();
  await page.getByRole("button", { name: "খসড়া রাখুন" }).click();
  await expect(toast(page, "খসড়া সংরক্ষণ হয়েছে।")).toBeVisible();
  await expect(page.getByText("৳৬,৮২০").first()).toBeVisible();

  // The draft is changed, and then confirmed.
  await page.getByRole("link", { name: "অর্ডার বদলান" }).click();
  await expect(heading(page, "অর্ডার বদলান")).toBeVisible();
  await expect(label(page, "ডেলিভারি চার্জ (৳)")).toHaveValue("100");
  await page.getByRole("button", { name: "একটি বাড়ান" }).click();
  await label(page, "ডেলিভারি চার্জ (৳)").fill("0");
  await page.getByRole("button", { name: "খসড়া রাখুন" }).click();
  await expect(toast(page, "খসড়া সংরক্ষণ হয়েছে।")).toBeVisible();
  await expect(page.getByText("৳১৩,৫২০").first()).toBeVisible();
  await page.getByRole("button", { name: "নিশ্চিত করুন", exact: true }).click();
  await expect(toast(page, "অর্ডার নিশ্চিত হয়েছে।")).toBeVisible();

  // A customer's page starts an order with their number, and lists this one.
  await page.goto("/customers");
  await page.getByRole("link", { name: /নাসরিন/ }).click();
  await page.getByRole("link", { name: /১০০১/ }).click();
  await expect(heading(page, /১০০১/)).toBeVisible();
});

test("confirming needs an address and a district, and says so before saving anything", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await product(page.request, "ওয়ালেট", 2);
  await page.goto("/orders/new");
  await label(page, "ফোন নম্বর").fill(newPhone());
  await label(page, "গ্রাহকের নাম").fill("করিম");
  await addItem(page, "ওয়ালেট");
  await page.getByRole("button", { name: "সংরক্ষণ করে নিশ্চিত করুন" }).click();
  await expect(label(page, "ঠিকানা")).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByText("নিশ্চিত করতে এটি লাগবে।")).toHaveCount(2);
  await expect(label(page, "ঠিকানা")).toBeFocused();
  expect(((await (await page.request.get("/api/v1/orders")).json()) as { items: unknown[] }).items).toHaveLength(0);
});

test("the server's refusal of a number lands under the number", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await product(page.request, "ওয়ালেট", 2);
  await page.goto("/orders/new");
  await label(page, "ফোন নম্বর").fill("12345");
  await label(page, "গ্রাহকের নাম").fill("করিম");
  await addItem(page, "ওয়ালেট");
  await page.getByRole("button", { name: "খসড়া রাখুন" }).click();
  await expect(label(page, "ফোন নম্বর")).toHaveAttribute("aria-invalid", "true");
  await expect(label(page, "ফোন নম্বর")).toBeFocused();
});

test("two people confirming the last unit: one gets it, the other is told why not", async ({ page, browser }) => {
  await signUpWithBusiness(page.request);
  const last = await product(page.request, "শেষ কুর্তি", 1);
  const first = await makeOrder(page.request, last.variantId, { confirmed: false, name: "এক" });
  const second = await makeOrder(page.request, last.variantId, { confirmed: false, name: "দুই" });

  const other = await signedInElsewhere(browser, page);
  await page.goto(`/orders/${first.id}`);
  await other.goto(`/orders/${second.id}`);
  const confirmOne = page.getByRole("button", { name: "নিশ্চিত করুন", exact: true });
  const confirmTwo = other.getByRole("button", { name: "নিশ্চিত করুন", exact: true });
  await expect(confirmOne).toBeVisible();
  await expect(confirmTwo).toBeVisible();

  await Promise.all([confirmOne.click(), confirmTwo.click()]);

  const refused = "শেষ কুর্তি: মাত্র ০টি আছে।";
  await expect
    .poll(async () => {
      const shown = await Promise.all([page.getByText(refused).count(), other.getByText(refused).count()]);
      return shown[0] + shown[1];
    })
    .toBe(1);
  expect(await available(page.request, last.id)).toBe(0);
  await other.context().close();
});

async function signedInElsewhere(browser: Browser, page: Page): Promise<Page> {
  const context = await browser.newContext({
    storageState: await page.context().storageState(),
    locale: "bn-BD",
    timezoneId: "Asia/Dhaka",
    viewport: page.viewportSize(),
  });
  return context.newPage();
}

test("a double tap makes one order", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await product(page.request, "ওয়ালেট", 5);
  await page.goto("/orders/new");
  await label(page, "ফোন নম্বর").fill(newPhone());
  await label(page, "গ্রাহকের নাম").fill("করিম");
  await addItem(page, "ওয়ালেট");

  await page.getByRole("button", { name: "খসড়া রাখুন" }).dblclick();

  await expect(toast(page, "খসড়া সংরক্ষণ হয়েছে।")).toBeVisible();
  const orders = (await (await page.request.get("/api/v1/orders")).json()) as { items: unknown[] };
  expect(orders.items).toHaveLength(1);
});

test("saving again after the answer was lost makes one order", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await product(page.request, "ওয়ালেট", 5);
  await page.goto("/orders/new");
  await label(page, "ফোন নম্বর").fill(newPhone());
  await label(page, "গ্রাহকের নাম").fill("করিম");
  await addItem(page, "ওয়ালেট");

  // The server makes the order, and the answer never reaches the phone.
  let lost = false;
  await page.route("**/api/v1/orders", async (route) => {
    if (route.request().method() === "POST" && !lost) {
      lost = true;
      await route.fetch();
      await route.abort("connectionreset");
    } else {
      await route.continue();
    }
  });
  await page.getByRole("button", { name: "খসড়া রাখুন" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "সার্ভারে পৌঁছানো যাচ্ছে না" })).toBeVisible();
  await page.getByRole("button", { name: "খসড়া রাখুন" }).click();

  await expect(toast(page, "খসড়া সংরক্ষণ হয়েছে।")).toBeVisible();
  const orders = (await (await page.request.get("/api/v1/orders")).json()) as { items: unknown[] };
  expect(orders.items).toHaveLength(1);
});

test("refused items at the door: less to collect, and what becomes of them", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "কুর্তি", 5);
  const order = await makeOrder(page.request, kurti.variantId, { quantity: 2 });
  await post(page.request, `/api/v1/orders/${order.id}/ship`, { courier: "RedX" });
  await page.goto(`/orders/${order.id}`);

  await page.getByRole("button", { name: "ডেলিভারি হয়েছে" }).click();
  const deliver = page.getByRole("dialog");
  await expect(label(deliver, "কুরিয়ার কত আদায় করেছে (৳)")).toHaveValue("3060");
  await deliver.getByLabel("গ্রাহক কিছু পণ্য নেননি").check();
  await label(deliver.getByRole("group", { name: "কুর্তি" }), "কয়টি").fill("1");
  await expect(label(deliver, "কুরিয়ার কত আদায় করেছে (৳)")).toHaveValue("1560");
  await label(deliver.getByRole("group", { name: "কুর্তি" }), "পণ্যটির কী হবে").selectOption("WRITE_OFF");
  await expectAccessible(page);
  await deliver.getByRole("button", { name: "ডেলিভারি হয়েছে" }).click();

  await expect(toast(page, "ডেলিভারি লেখা হয়েছে।")).toBeVisible();
  await expect(page.getByText("কিছু পণ্য নেননি")).toBeVisible();
  await expect(page.getByText(/নষ্ট, স্টকে নয়/)).toBeVisible();
  await expect(page.getByText("ডেলিভারির সময় গ্রাহক নেননি")).toBeVisible();
  expect(await available(page.request, kurti.id)).toBe(3);
});

test("a parcel that came back goes on the shelf, and can be sent again", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "কুর্তি", 5);
  const order = await makeOrder(page.request, kurti.variantId);
  await post(page.request, `/api/v1/orders/${order.id}/ship`, { courier: "RedX" });
  await page.goto(`/orders/${order.id}`);

  await page.getByRole("button", { name: "পার্সেল ফেরত এসেছে" }).click();
  const back = page.getByRole("dialog");
  await back.getByRole("button", { name: "পার্সেল ফেরত এসেছে" }).click();
  await expect(label(back, "কারণ")).toHaveAttribute("aria-invalid", "true");
  await label(back, "কারণ").fill("গ্রাহক ফোন ধরেননি");
  await back.getByRole("button", { name: "পার্সেল ফেরত এসেছে" }).click();
  await expect(toast(page, "পার্সেল ফেরত লেখা হয়েছে।")).toBeVisible();
  expect(await available(page.request, kurti.id)).toBe(5);

  await page.getByRole("button", { name: "আবার কুরিয়ারে দিন" }).click();
  const ship = page.getByRole("dialog");
  await label(ship, "কুরিয়ার").fill("Steadfast");
  await ship.getByRole("button", { name: "কুরিয়ারে দিন" }).click();
  await expect(toast(page, "অর্ডার কুরিয়ারে গেছে।")).toBeVisible();
  await expect(page.getByRole("button", { name: "ডেলিভারি হয়েছে" })).toBeVisible();
  expect(await available(page.request, kurti.id)).toBe(4);
});

test("a payment lowers what the courier collects, and cancelling gives the stock back", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "কুর্তি", 5);
  const order = await makeOrder(page.request, kurti.variantId);
  await page.goto(`/orders/${order.id}`);
  await expect(page.getByText("৳১,৫৬০").first()).toBeVisible();

  await page.getByRole("button", { name: "পেমেন্ট যোগ করুন" }).click();
  const pay = page.getByRole("dialog");
  await expect(label(pay, "টাকার অঙ্ক (৳)")).toHaveValue("1560");
  await label(pay, "টাকার অঙ্ক (৳)").fill("9999");
  await pay.getByRole("button", { name: "পেমেন্ট সংরক্ষণ করুন" }).click();
  await expect(pay.getByRole("alert")).toBeVisible();
  await label(pay, "টাকার অঙ্ক (৳)").fill("560");
  await label(pay, "কীভাবে দিয়েছেন").selectOption("NAGAD");
  await label(pay, "ট্রানজেকশন আইডি (ঐচ্ছিক)").fill("TX1");
  await pay.getByRole("button", { name: "পেমেন্ট সংরক্ষণ করুন" }).click();
  await expect(toast(page, "পেমেন্ট যোগ হয়েছে।")).toBeVisible();
  await expect(page.getByText(/TX1/)).toBeVisible();
  await expect(page.locator("dd").filter({ hasText: "৳১,০০০" })).toBeVisible();

  await page.getByRole("button", { name: "অর্ডার বাতিল করুন" }).click();
  const cancel = page.getByRole("dialog");
  await label(cancel, "কারণ").fill("গ্রাহক চান না");
  await cancel.getByRole("button", { name: "অর্ডার বাতিল করুন" }).click();
  await expect(toast(page, "অর্ডার বাতিল হয়েছে।")).toBeVisible();
  expect(await available(page.request, kurti.id)).toBe(5);
  await expect(page.getByRole("link", { name: "অর্ডার বদলান" })).toHaveCount(0);
});

test("the list counts by status, filters, and packs and ships together, telling what failed", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "কুর্তি", 20);
  const one = await makeOrder(page.request, kurti.variantId, { name: "প্রথম" });
  const two = await makeOrder(page.request, kurti.variantId, { name: "দ্বিতীয়" });
  const three = await makeOrder(page.request, kurti.variantId, { name: "তৃতীয়" });
  await makeOrder(page.request, kurti.variantId, { name: "খসড়া জন", confirmed: false });
  await page.goto("/orders");

  const tabs = page.getByRole("navigation", { name: "অবস্থা অনুযায়ী অর্ডার" });
  await expect(tabs.getByRole("link", { name: "সব ৪" })).toBeVisible();
  await expect(tabs.getByRole("link", { name: "নিশ্চিত ৩" })).toBeVisible();
  await expectAccessible(page);
  await tabs.getByRole("link", { name: "খসড়া ১" }).click();
  await expect(page.getByRole("link", { name: /খসড়া জন/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /প্রথম/ })).toHaveCount(0);

  // By number and by phone, however they're typed; the counts follow.
  await tabs.getByRole("link", { name: "সব ৪" }).click();
  const search = page.getByRole("searchbox", { name: "অর্ডার খুঁজুন" });
  const find = page.getByRole("button", { name: "খুঁজুন", exact: true });
  await search.fill(one.phone);
  // Typing only waits in the panel, until the button or Enter.
  await expect(page.getByRole("link", { name: /দ্বিতীয়/ })).toBeVisible();
  await find.click();
  await expect(page.getByRole("link", { name: /প্রথম/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /দ্বিতীয়/ })).toHaveCount(0);
  await expect(tabs.getByRole("link", { name: "সব ১" })).toBeVisible();
  await expect(page).toHaveURL(/q=/);
  await expectAccessible(page);
  await search.fill(two.label);
  await search.press("Enter");
  await expect(page.getByRole("link", { name: /দ্বিতীয়/ })).toBeVisible();
  // A chip takes the search off.
  await page.getByRole("button", { name: /মুছুন$/ }).click();
  await expect(search).toHaveValue("");
  await expect(tabs.getByRole("link", { name: "সব ৪" })).toBeVisible();
  // Nothing in these dates, and a way back.
  await label(page, "তারিখ পর্যন্ত").fill("2020-01-01");
  await find.click();
  await expect(page.getByText("এই খোঁজে কোনো অর্ডার নেই।")).toBeVisible();
  await page.getByRole("button", { name: "রিসেট", exact: true }).last().click();
  await expect(tabs.getByRole("link", { name: "সব ৪" })).toBeVisible();
  await expect(label(page, "তারিখ পর্যন্ত")).toHaveValue("");

  // Pack two; one of them is cancelled by someone else first.
  await tabs.getByRole("link", { name: /^নিশ্চিত/ }).click();
  await expect(page.getByRole("link", { name: /প্রথম/ })).toBeVisible();
  await page.getByRole("checkbox", { name: `বেছে নিন: ${one.label}` }).check();
  await page.getByRole("checkbox", { name: `বেছে নিন: ${three.label}` }).check();
  await post(page.request, `/api/v1/orders/${three.id}/cancel`, { reason: "গ্রাহক চান না" });
  await expectAccessible(page);
  await page.getByRole("button", { name: "প্যাক হয়েছে" }).click();
  await expect(page.getByText("১টি অর্ডার সম্পন্ন হয়েছে।")).toBeVisible();
  await expect(page.getByText(new RegExp(`^${three.label}:`))).toBeVisible();
  await expect(tabs.getByRole("link", { name: "প্যাক করা ১" })).toBeVisible();

  // Ship the packed one and a confirmed one, each with its own tracking number, together.
  await tabs.getByRole("link", { name: "সব" }).click();
  await page.getByRole("checkbox", { name: `বেছে নিন: ${one.label}` }).check();
  await page.getByRole("checkbox", { name: `বেছে নিন: ${two.label}` }).check();
  await page.getByRole("button", { name: "কুরিয়ারে দিন" }).click();
  const ship = page.getByRole("dialog");
  await ship.getByRole("button", { name: "Pathao" }).click();
  await label(ship, `ট্র্যাকিং নম্বর: ${one.label}, প্রথম`).fill("T-1");
  await label(ship, `ট্র্যাকিং নম্বর: ${two.label}, দ্বিতীয়`).fill("T-2");
  await expectAccessible(page);
  await ship.getByRole("button", { name: "কুরিয়ারে দিন" }).click();
  await expect(page.getByText("২টি অর্ডার সম্পন্ন হয়েছে।")).toBeVisible();
  await expect(tabs.getByRole("link", { name: "কুরিয়ারে ২" })).toBeVisible();

  // Printing many: one document, in the language on screen.
  await page.getByRole("checkbox", { name: `বেছে নিন: ${one.label}` }).check();
  await page.getByRole("checkbox", { name: `বেছে নিন: ${two.label}` }).check();
  const [slips] = await Promise.all([
    page.waitForEvent("popup"),
    page.getByRole("link", { name: "স্লিপ প্রিন্ট করুন" }).click(),
  ]);
  await expect(slips.getByText(one.label).first()).toBeVisible();
  await expect(slips.getByText(two.label).first()).toBeVisible();
});

test("COD to settle: what each courier owes, and marking its payout received", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const kurti = await product(page.request, "কুর্তি", 20);
  const delivered = async (courier: string) => {
    const order = await makeOrder(page.request, kurti.variantId);
    await post(page.request, `/api/v1/orders/${order.id}/ship`, { courier, trackingNo: `${courier}-1` });
    await post(page.request, `/api/v1/orders/${order.id}/deliver`);
    return order;
  };
  await page.goto("/cod");
  await expect(page.getByText("কোনো কুরিয়ারের কাছে এখন COD-এর টাকা নেই।")).toBeVisible();

  const first = await delivered("Pathao");
  const second = await delivered("pathao");
  await delivered("RedX");
  const onTheWay = await makeOrder(page.request, kurti.variantId);
  await post(page.request, `/api/v1/orders/${onTheWay.id}/ship`, { courier: "RedX" });

  await page.goto("/more");
  await page.getByRole("link", { name: "COD হিসাব" }).click();
  await expect(heading(page, "COD হিসাব")).toBeVisible();
  const pathao = page.locator("section").filter({ has: page.getByRole("heading", { name: /pathao/i }) });
  const redx = page.locator("section").filter({ has: page.getByRole("heading", { name: "RedX" }) });
  await expect(pathao.getByText("পাওনা ৳৩,১২০, ২টি পার্সেল")).toBeVisible();
  await expect(redx.getByText("পাওনা ৳১,৫৬০, ১টি পার্সেল")).toBeVisible();
  await expect(redx.getByText("আরও ৳১,৫৬০ এখনো পথে")).toBeVisible();
  await expectAccessible(page);

  // This payout covers one of the two.
  await pathao.getByRole("checkbox", { name: `বেছে নিন: ${second.label}` }).uncheck();
  await pathao.getByRole("button", { name: "৳১,৫৬০ বুঝে পেয়েছি" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "৳১,৫৬০ বুঝে পেয়েছি" }).click();
  await expect(pathao.getByText("পাওনা ৳১,৫৬০, ১টি পার্সেল")).toBeVisible();
  await expect(pathao.getByRole("link", { name: first.label })).toHaveCount(0);
  await expect(pathao.getByRole("link", { name: second.label })).toBeVisible();

  await page.goto(`/orders/${first.id}`);
  await expect(page.getByText(/বুঝে পেয়েছেন/)).toBeVisible();
});
