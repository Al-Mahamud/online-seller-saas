import type { APIRequestContext, Page } from "@playwright/test";
import { joinAsStaff, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

async function post(request: APIRequestContext, path: string, data?: unknown) {
  await request.get("/api/v1/auth/csrf");
  const token = (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
  const answer = await request.post(path, { headers: { "X-XSRF-TOKEN": token }, data });
  expect(answer.ok(), `${path}: ${await answer.text()}`).toBe(true);
  return (await answer.json()) as Record<string, unknown>;
}

/** A kurti in S and M, ৳1,500 each, costing ৳900, 10 of each in stock. Returns the S. */
async function kurti(request: APIRequestContext) {
  const product = await post(request, "/api/v1/products", {
    name: "সুতি কুর্তি",
    price: 150000,
    costPrice: 90000,
    openingStock: 10,
    options: [{ name: "সাইজ", values: ["S", "M"] }],
  });
  return (product.variants as { id: string }[])[0]?.id as string;
}

/** Visible only: the list is a table on laptops and cards on phones. */
const shown = (page: Page, text: string | RegExp) => page.getByText(text).filter({ visible: true }).first();
const tile = (page: Page, name: string) =>
  page.getByText(name, { exact: true }).filter({ visible: true }).locator("..");

test("records damaged goods and lists them with their loss", async ({ page }, info) => {
  await signUpWithBusiness(page.request);
  await kurti(page.request);

  // Found from the menu: the sidebar on laptops, "More" on phones.
  await page.goto(info.project.name === "phone" ? "/more" : "/");
  await page.getByRole("link", { name: "নষ্ট পণ্য" }).filter({ visible: true }).click();
  await expect(page.getByRole("heading", { level: 1, name: "নষ্ট পণ্য" })).toBeVisible();
  await expect(page.getByText("এই সময়ে কিছু নষ্ট হয়নি।")).toBeVisible();
  await expectAccessible(page);

  await page
    .getByRole("button", { name: /^(নষ্ট পণ্য )?লিখুন$/ })
    .first()
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "সংরক্ষণ করুন" }).click();
  await expect(dialog.getByText("অন্তত একটি পণ্য যোগ করুন।")).toBeVisible();

  await dialog.getByRole("searchbox", { name: "কোন পণ্য?" }).fill("কুর্তি");
  await dialog.getByRole("button", { name: /সুতি কুর্তি/ }).click();
  await dialog.getByRole("button", { name: /^S.*যোগ করুন$/ }).click();
  const quantity = dialog.getByRole("textbox", { name: "সুতি কুর্তি, S: নষ্ট কতগুলো" });
  await quantity.fill("২০");
  await expect(dialog.getByText(/স্টকে বিক্রয়যোগ্য আছে ১০টি/)).toBeVisible();
  await quantity.fill("২");
  await expect(dialog.getByText("স্টক থাকবে ৮টি")).toBeVisible();
  await expect(dialog.getByText("ক্ষতি ৳১,৮০০")).toBeVisible();

  await dialog.getByRole("button", { name: "২টি নষ্ট লিখুন" }).click();
  await expect(dialog.getByText("কী হয়েছে, বেছে নিন।")).toBeVisible();
  await dialog.getByRole("button", { name: "দাগ বা রং নষ্ট" }).click();
  await dialog.getByLabel("নোট (ঐচ্ছিক)").fill("বৃষ্টিতে ভিজে গেছে");
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "২টি নষ্ট লিখুন" }).click();
  await expect(dialog).toBeHidden();

  await expect(shown(page, "বৃষ্টিতে ভিজে গেছে")).toBeVisible();
  await expect(shown(page, "দাগ বা রং নষ্ট")).toBeVisible();
  await expect(tile(page, "নষ্ট হয়েছে")).toContainText("২টি পণ্য");
  await expect(tile(page, "ক্ষতি (কেনা দামে)")).toContainText("৳১,৮০০");
  await expect(page.getByRole("heading", { name: "কোন পণ্যে বেশি ক্ষতি" })).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "ফেরত এসে", exact: true }).click();
  await expect(page.getByText("এই খোঁজে কোনো নষ্ট পণ্য নেই।")).toBeVisible();
  await page.getByRole("button", { name: "সব দেখুন" }).click();
  await expect(shown(page, "বৃষ্টিতে ভিজে গেছে")).toBeVisible();

  const product = (await (await page.request.get("/api/v1/products?q=কুর্তি")).json()) as {
    items: { available: number }[];
  };
  expect(product.items[0]?.available).toBe(18);
});

test("staff see what was damaged, and no money", async ({ page, browser }) => {
  await signUpWithBusiness(page.request);
  const small = await kurti(page.request);
  await post(page.request, "/api/v1/stock/adjustments", {
    lines: [{ variantId: small, kind: "DAMAGE", quantity: 3 }],
    reason: "LOST",
  });
  const staff = await joinAsStaff(page, browser);

  await staff.goto("/damages");
  await expect(shown(staff, "হারিয়ে গেছে")).toBeVisible();
  await expect(tile(staff, "নষ্ট হয়েছে")).toContainText("৩টি পণ্য");
  await expect(staff.getByText("ক্ষতি (কেনা দামে)")).toHaveCount(0);
  await expect(staff.getByRole("heading", { name: "কোন পণ্যে বেশি ক্ষতি" })).toHaveCount(0);
  await expectAccessible(staff);
  await staff.context().close();
});
