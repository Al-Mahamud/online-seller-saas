import type { APIRequestContext, Page } from "@playwright/test";
import { signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

async function customers(request: APIRequestContext, count: number) {
  const token = (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
  for (let index = 0; index < count; index++) {
    const answer = await request.post("/api/v1/customers", {
      headers: { "X-XSRF-TOKEN": token },
      data: { name: `গ্রাহক ${index + 1}`, phone: `0171${String(1000000 + index)}` },
    });
    expect(answer.ok(), await answer.text()).toBe(true);
  }
}

const pager = (page: Page) => page.getByRole("navigation", { name: "পৃষ্ঠা" });
const showing = (page: Page, text: string) => expect(page.getByRole("status").filter({ hasText: text })).toBeVisible();

test("a long list goes page by page, and its place is kept in the address", async ({ page }, info) => {
  await signUpWithBusiness(page.request);
  await customers(page.request, 45);
  await page.goto("/customers");

  await showing(page, "৪৫টির মধ্যে ১–২০");
  await expect(page.getByRole("link", { name: /গ্রাহক/ }).filter({ hasText: "০১" })).toHaveCount(20);
  const previous = pager(page).getByRole("button", { name: "আগের" });
  const next = pager(page).getByRole("button", { name: "পরের" });
  await expect(previous).toBeDisabled();
  await expectAccessible(page);

  await next.click();
  await expect(page).toHaveURL(/page=2/);
  await showing(page, "৪৫টির মধ্যে ২১–৪০");
  await expect(previous).toBeEnabled();

  // A number on a laptop, where they fit; "page 2 of 3" on a phone, where they don't.
  if (info.project.name === "laptop") {
    await expect(pager(page).getByRole("button", { name: "পৃষ্ঠা ২" })).toHaveAttribute("aria-current", "page");
    await pager(page).getByRole("button", { name: "পৃষ্ঠা ৩" }).click();
  } else {
    await expect(pager(page).getByText("পৃষ্ঠা ২ / ৩")).toBeVisible();
    await next.click();
  }
  await showing(page, "৪৫টির মধ্যে ৪১–৪৫");
  await expect(next).toBeDisabled();
  await expectAccessible(page);

  // A reload stays, and Back goes back a page.
  await page.reload();
  await showing(page, "৪৫টির মধ্যে ৪১–৪৫");
  await page.goBack();
  await showing(page, "৪৫টির মধ্যে ২১–৪০");

  // More on a page, from the start: all of it fits, so only the total is left.
  await page.getByLabel("প্রতি পৃষ্ঠায়").selectOption("50");
  await expect(page).toHaveURL(/size=50/);
  await expect(page).not.toHaveURL(/page=/);
  await showing(page, "মোট ৪৫টি");
  await expect(pager(page)).toHaveCount(0);
});

test("a page past the end goes to the last one, and Back doesn't return to it", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await customers(page.request, 21);
  await page.goto("/customers");
  await showing(page, "২১টির মধ্যে ১–২০");

  await page.goto("/customers?page=99");
  await showing(page, "২১টির মধ্যে ২১–২১");
  await expect(page).toHaveURL(/page=2/);
  await page.goBack();
  await showing(page, "২১টির মধ্যে ১–২০");

  // Nonsense in the address is the first page.
  await page.goto("/customers?page=first&size=7");
  await showing(page, "২১টির মধ্যে ১–২০");
});

test("searching starts again from the first page, with its own total", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await customers(page.request, 25);
  await page.goto("/customers?page=2");
  await showing(page, "২৫টির মধ্যে ২১–২৫");

  await page.getByRole("searchbox", { name: "গ্রাহক খুঁজুন" }).fill("1000016");
  await expect(page).not.toHaveURL(/page=/);
  await showing(page, "মোট ১টি");
});

test("a short list only says how many", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await customers(page.request, 2);
  await page.goto("/customers");

  await showing(page, "মোট ২টি");
  await expect(pager(page)).toHaveCount(0);
});

test("an empty list has no pager at all", async ({ page }) => {
  await signUpWithBusiness(page.request);
  await page.goto("/customers");

  await expect(page.getByText("এখনো কোনো গ্রাহক নেই।")).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "মোট" })).toHaveCount(0);
});
