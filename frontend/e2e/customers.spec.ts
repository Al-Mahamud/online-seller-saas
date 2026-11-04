import type { APIRequestContext, Page } from "@playwright/test";
import { newPhone, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const label = (scope: Page | ReturnType<Page["getByRole"]>, name: string) => scope.getByLabel(name, { exact: true });
const heading = (page: Page, name: string) => page.getByRole("heading", { level: 1, name });

async function xsrf(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  return (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

async function post(request: APIRequestContext, path: string, data: unknown) {
  const answer = await request.post(path, { headers: { "X-XSRF-TOKEN": await xsrf(request) }, data });
  expect(answer.ok(), `${path}: ${await answer.text()}`).toBe(true);
  return (await answer.json()) as Record<string, never>;
}

/** Chooses from a place list by searching for it, in whichever language. */
async function pick(page: Page, field: RegExp, search: string, name: string) {
  await page.getByRole("button", { name: field }).click();
  const list = page.getByRole("dialog").last();
  await label(list, "খুঁজুন").fill(search);
  await list.getByRole("button", { name, exact: true }).click();
}

test("adds a customer with a place, finds them by any phone format, and edits them", async ({ page }, info) => {
  await signUpWithBusiness(page.request);
  const phone = newPhone();
  await page.goto("/more");
  await page.getByRole("link", { name: "গ্রাহক" }).click();
  await expect(heading(page, "গ্রাহক")).toBeVisible();
  await expect(page.getByText("এখনো কোনো গ্রাহক নেই।")).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "নতুন গ্রাহক" }).first().click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(label(dialog, "গ্রাহকের নাম")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "গ্রাহকের নাম").fill("রহিমা খাতুন");
  await label(dialog, "ফোন নম্বর").fill("not a number");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(label(dialog, "ফোন নম্বর")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "ফোন নম্বর").fill(phone);
  await label(dialog, "ঠিকানা (ঐচ্ছিক)").fill("বাসা ১২, রোড ৩");
  await expectAccessible(page);

  // Searching in English finds the Bangla name, and the area list follows the district.
  await pick(page, /^জেলা \(ঐচ্ছিক\)/, "Dhaka", "ঢাকা");
  await expectAccessible(page);
  await pick(page, /^থানা বা উপজেলা \(ঐচ্ছিক\)/, "Mirpur", "মিরপুর");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();

  await expect(heading(page, "রহিমা খাতুন")).toBeVisible();
  await expect(page.getByText("মিরপুর, ঢাকা")).toBeVisible();
  await expectAccessible(page);

  // Found however the number is written, as sellers copy them from chats.
  const local = phone.slice(1);
  for (const written of [
    phone,
    `${phone.slice(0, 5)}-${phone.slice(5)}`,
    `+880 ${local}`,
    `880${local}`,
    phone.replace(/\d/g, (digit) => "০১২৩৪৫৬৭৮৯"[Number(digit)] as string),
  ]) {
    await page.goto(`/customers?q=${encodeURIComponent(written)}`);
    await expect(page.getByRole("link", { name: /রহিমা খাতুন/ }).first()).toBeVisible();
  }
  await page.goto("/customers");
  // The list follows the search button, with a chip and a reset to go back.
  const search = page.getByRole("search", { name: "গ্রাহক খুঁজুন" });
  await search.getByRole("searchbox").fill("রহিমা");
  await search.getByRole("button", { name: "খুঁজুন" }).click();
  await expect(page.getByRole("link", { name: /রহিমা খাতুন/ }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: /"রহিমা" মুছুন/ })).toBeVisible();
  await search.getByRole("searchbox").fill("কেউ নেই");
  await search.getByRole("button", { name: "খুঁজুন" }).click();
  await expect(page.getByText("এই খোঁজে কোনো গ্রাহক পাওয়া যায়নি।")).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("button", { name: "সব গ্রাহক দেখুন" }).click();
  await expect(page.getByRole("link", { name: /রহিমা খাতুন/ }).first()).toBeVisible();
  await expect(page.getByText("মোট ১ জন গ্রাহক")).toBeVisible();
  // On a phone, a customer can be rung from the list.
  if (info.project.name === "phone") {
    await expect(page.getByRole("link", { name: "রহিমা খাতুন-কে ফোন করুন" })).toHaveAttribute("href", /^tel:/);
  }
  await expectAccessible(page);

  // Editing, and a number someone else has is refused under its box.
  const other = newPhone();
  await post(page.request, "/api/v1/customers", { name: "অন্য গ্রাহক", phone: other });
  await page.goto("/customers");
  await page
    .getByRole("link", { name: /রহিমা খাতুন/ })
    .first()
    .click();
  await page.getByRole("button", { name: "তথ্য বদলান" }).click();
  await label(dialog, "ফোন নম্বর").fill(other);
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(label(dialog, "ফোন নম্বর")).toHaveAttribute("aria-invalid", "true");
  await label(dialog, "ফোন নম্বর").fill(phone);
  await label(dialog, "গ্রাহকের নাম").fill("রহিমা বেগম");
  await page.getByRole("button", { name: /^সরান: জেলা/ }).click();
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(heading(page, "রহিমা বেগম")).toBeVisible();
  await expect(page.getByText("মিরপুর, ঢাকা")).toBeHidden();
});

test("shows a customer's orders, and refuses deleting one with an order in progress", async ({ page }) => {
  await signUpWithBusiness(page.request);
  const product = await post(page.request, "/api/v1/products", { name: "কুর্তি", price: 150000, openingStock: 5 });
  const variantId = ((product.variants as unknown as { id: string }[])[0] as { id: string }).id;
  const customer = await post(page.request, "/api/v1/customers", { name: "করিম", phone: newPhone() });
  const order = await post(page.request, "/api/v1/orders", {
    customerId: customer.id,
    items: [{ variantId, quantity: 2 }],
    deliveryCharge: 6000,
  });

  await page.goto(`/customers/${customer.id}`);
  await expect(page.getByText("#১০০১")).toBeVisible();
  await expect(page.getByText("৳৩,০৬০")).toBeVisible();
  await expect(page.getByText("খসড়া")).toBeVisible();

  await expectAccessible(page);
  await page.getByRole("button", { name: "গ্রাহক মুছুন" }).click();
  const confirm = page.getByRole("dialog");
  await confirm.getByRole("button", { name: "গ্রাহক মুছুন" }).click();
  // Refused: it says why in the dialog, which stays.
  await expect(confirm.getByRole("alert")).toContainText("এই গ্রাহকের অর্ডার চলমান আছে।");
  await expect(heading(page, "করিম")).toBeHidden();
  await confirm.getByRole("button", { name: "বাতিল" }).click();
  await expect(heading(page, "করিম")).toBeVisible();

  // Once it's cancelled, they can go; the order stays.
  await post(page.request, `/api/v1/orders/${order.id}/cancel`, { reason: "গ্রাহক চান না" });
  await page.getByRole("button", { name: "গ্রাহক মুছুন" }).click();
  await expect(confirm.getByRole("alert")).toHaveCount(0);
  await confirm.getByRole("button", { name: "গ্রাহক মুছুন" }).click();
  await expect(heading(page, "গ্রাহক")).toBeVisible();
  await expect(page.getByRole("link", { name: /করিম/ })).toBeHidden();
});

test("a zone's charge is what an order is quoted", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/more");
  await page.getByRole("link", { name: "ডেলিভারি চার্জ" }).click();
  await expect(heading(page, "ডেলিভারি চার্জ")).toBeVisible();
  // A new business starts with two zones, named in the seller's language; everywhere else stays last.
  const inside = page.getByRole("region", { name: "ঢাকার ভেতরে" });
  await expect(label(inside, "অঞ্চলের নাম")).toHaveValue("ঢাকার ভেতরে");
  await expect(inside.getByRole("button", { name: "ঢাকা সরান" })).toBeVisible();
  await expect(label(page.getByRole("region", { name: "বাকি সব জায়গা" }), "চার্জ")).toHaveValue("130");
  await expectAccessible(page);

  // A new zone, its charge by the steppers, and a district picked from the list.
  await page.getByRole("button", { name: "নতুন অঞ্চল যোগ করুন" }).click();
  const zone = page.getByRole("region", { name: "নতুন অঞ্চল" });
  await label(zone, "অঞ্চলের নাম").fill("চট্টগ্রাম জেলা");
  const named = page.getByRole("region", { name: "চট্টগ্রাম জেলা" });
  await label(named, "চার্জ").fill("৮৫");
  await named.getByRole("button", { name: "চট্টগ্রাম জেলা: ১০ টাকা বেশি" }).click();
  await expect(label(named, "চার্জ")).toHaveValue("95");
  await named.getByRole("button", { name: "জেলা বা থানা যোগ করুন" }).click();
  const list = page.getByRole("dialog", { name: "জায়গা যোগ করুন" });
  await list.getByRole("searchbox", { name: "নাম দিয়ে খুঁজুন" }).fill("Chattogram");
  await list.getByRole("checkbox", { name: "চট্টগ্রাম", exact: true }).check();
  await expectAccessible(page);
  await list.getByRole("button", { name: "১টি যোগ করুন" }).click();
  await expect(named.getByRole("button", { name: "চট্টগ্রাম সরান" })).toBeVisible();

  // Dhaka moves here from the other zone, says so, and can go back.
  await named.getByRole("button", { name: "জেলা বা থানা যোগ করুন" }).click();
  await list.getByRole("searchbox", { name: "নাম দিয়ে খুঁজুন" }).fill("Dhaka");
  await expect(list.getByText("ঢাকার ভেতরে-এ আছে · বাছলে এখানে চলে আসবে")).toBeVisible();
  await list.getByRole("checkbox", { name: /^ঢাকা ঢাকার ভেতরে/ }).check();
  await list.getByRole("button", { name: "১টি যোগ করুন" }).click();
  await expect(named.getByText("ঢাকা আগে “ঢাকার ভেতরে”-তে ছিল, এখন এখানে।")).toBeVisible();
  await expect(inside.getByText("এখনো কোনো জায়গা নেই")).toBeVisible();
  await named.getByRole("button", { name: "ফিরিয়ে দিন" }).click();
  await expect(inside.getByRole("button", { name: "ঢাকা সরান" })).toBeVisible();

  const bar = page.getByRole("region", { name: "সংরক্ষণ হয়নি এমন পরিবর্তন" });
  await expect(bar).toContainText("অঞ্চলের পরিবর্তন সংরক্ষণ হয়নি");
  await bar.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(page.getByText("ডেলিভারি চার্জ সংরক্ষণ করা হয়েছে।")).toBeVisible();
  await expect(bar).toBeHidden();

  // The check on the page is the server's own quote, the one the order form will use.
  const districts = (await (await page.request.get("/api/v1/geo/districts")).json()) as {
    id: number;
    nameEn: string;
  }[];
  const chattogram = districts.find((district) => /chatt?ogram|chittagong/i.test(district.nameEn));
  await page.getByRole("combobox", { name: "জেলা", exact: true }).selectOption(String(chattogram?.id));
  const answer = page.getByRole("status").filter({ hasText: "চট্টগ্রাম জেলা" });
  await expect(answer).toContainText("৳৯৫");
  await expect(answer).toContainText("জেলা মিলেছে");
  const quote = (await (
    await page.request.get(`/api/v1/delivery-zones/quote?districtId=${chattogram?.id}`)
  ).json()) as {
    charge: number;
  };
  expect(quote.charge).toBe(9500);

  // It stays after a refresh, and a bad charge or a repeated name is refused before saving.
  await page.reload();
  const saved = page.getByRole("region", { name: "চট্টগ্রাম জেলা" });
  await expect(label(saved, "চার্জ")).toHaveValue("95");
  await label(saved, "চার্জ").fill("many");
  await bar.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(bar.getByRole("alert")).toContainText("১টি ঘর ঠিক করলে সংরক্ষণ হবে");
  await expect(page.getByText("সঠিক একটি চার্জ দিন, যেমন ৮০।")).toBeVisible();
  await expect(label(saved, "চার্জ")).toHaveAttribute("aria-invalid", "true");
  await expectAccessible(page);
  await label(saved, "অঞ্চলের নাম").fill("ঢাকার ভেতরে");
  await expect(bar.getByRole("alert")).toContainText("২টি ঘর ঠিক করলে সংরক্ষণ হবে");
  await expect(page.getByText("এই নামে আরেকটি অঞ্চল আছে।")).toBeVisible();
  await bar.getByRole("button", { name: "বাতিল" }).click();
  await expect(bar).toBeHidden();
});
