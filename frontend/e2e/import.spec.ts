import { readFile } from "node:fs/promises";
import type { Page } from "@playwright/test";
import { signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

const label = (page: Page, name: string) => page.getByLabel(name, { exact: true });

/** A sheet of `count` products; the row at `badRow` (1 is the first product) has a price that isn't one. */
function sheet(count: number, badRow?: number): string {
  const rows = Array.from({ length: count }, (_, i) => {
    const number = i + 1;
    const price = number === badRow ? "not-a-price" : String(100 + number);
    return `আমদানি পণ্য ${number},IMPORT-${number},${price},50,7,আমদানি`;
  });
  return `${["Name", "SKU", "Price", "Cost", "Stock", "Category"].join(",")}\n${rows.join("\n")}\n`;
}

async function upload(page: Page, name: string, text: string) {
  await label(page, "পণ্যের ফাইল").setInputFiles({ name, mimeType: "text/csv", buffer: Buffer.from(text, "utf8") });
}

test("imports 20 products: one bad price is shown, and after fixing, all 20 are there", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/products");
  await page.getByRole("link", { name: "Excel থেকে আনুন" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Excel থেকে পণ্য আনুন" })).toBeVisible();
  await expectAccessible(page);

  await upload(page, "products.csv", sheet(20, 7));

  // The columns were recognised, and the problem is shown before anything changes.
  await expect(page.getByText("সমস্যা আছে ১")).toBeVisible();
  await expect(page.getByText("ঠিক আছে ১৯")).toBeVisible();
  await expect(label(page, "কলাম C: Price")).toHaveValue("PRICE");
  await expect(page.getByText(/কলাম C, দাম:/)).toBeVisible();
  await expect(page.getByText("নতুন ক্যাটাগরি তৈরি হবে: আমদানি")).toBeVisible();
  await expectAccessible(page);

  // The rows with problems come back as a file, to fix and upload again.
  const downloading = page.waitForEvent("download");
  await page.getByRole("link", { name: "সমস্যার সারিগুলোর ফাইল নামান" }).click();
  const download = await downloading;
  const errors = await readFile(await download.path(), "utf8");
  expect(errors).toContain("not-a-price");

  // The rest go in.
  await page.getByRole("button", { name: "১৯টি সারি আনুন" }).click();
  await expect(page.getByRole("heading", { name: "পণ্য আনা শেষ" })).toBeVisible();
  await expect(page.getByText("নতুন তৈরি ১৯")).toBeVisible();
  await expect(page.getByText("বাদ পড়েছে ১")).toBeVisible();
  await expectAccessible(page);

  // Fixing the file and uploading it adds the last one.
  await page.getByRole("button", { name: "আরেকটি ফাইল আনুন" }).click();
  await upload(page, "fixed.csv", errors.replace("not-a-price", "107"));
  await expect(page.getByText("ঠিক আছে ১")).toBeVisible();
  await page.getByRole("button", { name: "১টি সারি আনুন" }).click();
  await expect(page.getByText("নতুন তৈরি ১")).toBeVisible();

  await page.getByRole("link", { name: "পণ্য দেখুন" }).click();
  await expect(page.getByRole("link", { name: /আমদানি পণ্য/ }).filter({ visible: true })).toHaveCount(20);
  await page.getByRole("searchbox", { name: "পণ্য খুঁজুন" }).fill("IMPORT-7");
  await page.getByRole("searchbox", { name: "পণ্য খুঁজুন" }).press("Enter");
  await expect(page.getByRole("link", { name: /আমদানি পণ্য 7/ }).filter({ visible: true })).toBeVisible();
});

test("a column the server didn't recognise can be mapped by hand, and rows re-checked", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/products/import");

  await upload(page, "own.csv", "Name,Taka,Shirt Size\nশার্ট,500,M\nশার্ট,500,L\n");

  // No price column was recognised, so nothing can start yet, and the page says what it needs.
  await expect(page.getByRole("alert")).toContainText("বিক্রয় দাম");
  await expect(page.getByRole("button", { name: "০টি সারি আনুন" })).toBeDisabled();
  await expectAccessible(page);

  await label(page, "কলাম B: Taka").selectOption("PRICE");
  await expect(page.getByRole("alert").filter({ hasText: "বিক্রয় দাম" })).toBeHidden();

  // M and L read as the same variant until the size column counts.
  await expect(page.getByText("সমস্যা আছে ১")).toBeVisible();
  await label(page, "কলাম C: Shirt Size").selectOption("OPTION");
  await expect(page.getByText("ঠিক আছে ২")).toBeVisible();
  await expect(page.getByLabel("অপশনের নাম")).toHaveValue("Shirt Size");
  await expect(page.getByRole("button", { name: "২টি সারি আনুন" })).toBeEnabled();
  await page.getByRole("button", { name: "২টি সারি আনুন" }).click();
  await expect(page.getByRole("heading", { name: "পণ্য আনা শেষ" })).toBeVisible();

  await page.goto("/products");
  await page.getByRole("link", { name: /শার্ট/ }).filter({ visible: true }).click();
  await expect(page.getByRole("button", { name: "ইতিহাস: M" })).toBeVisible();
  await expect(page.getByRole("button", { name: "ইতিহাস: L" })).toBeVisible();
});

test("offers the template, says what's wrong with an unreadable file, and comes back after a refresh", async ({
  page,
}) => {
  await signUpWithBusiness(page.request);
  await page.goto("/products/import");

  const downloading = page.waitForEvent("download");
  await page.getByRole("link", { name: "নমুনা ফাইল নামান" }).click();
  expect((await downloading).suggestedFilename()).toBe("mojud-products.xlsx");

  await label(page, "পণ্যের ফাইল").setInputFiles({
    name: "photo.xlsx",
    mimeType: "application/octet-stream",
    buffer: Buffer.from("this is not a spreadsheet"),
  });
  await expect(page.getByRole("alert")).toContainText("কোনো সারি নেই");

  await upload(page, "products.csv", sheet(3));
  await expect(page.getByText("ঠিক আছে ৩")).toBeVisible();
  await expect(page).toHaveURL(/job=/);
  await page.reload();
  await expect(page.getByText("ঠিক আছে ৩")).toBeVisible();

  await page.getByRole("button", { name: "অন্য ফাইল বেছে নিন" }).click();
  await expect(page.getByRole("button", { name: "ফাইল বেছে নিন" })).toBeVisible();
});
