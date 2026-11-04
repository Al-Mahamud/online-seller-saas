import type { Page } from "@playwright/test";
import { test } from "./test";

const onPhone = () => test.info().project.name === "phone";

/** Days in a list's search panel: the date button on a laptop, the filter sheet on a phone. */
export async function pickDays(page: Page, from: string, to: string) {
  if (onPhone()) {
    await page.getByRole("button", { name: /^ফিল্টার/ }).click();
  } else {
    await page.getByRole("button", { name: /^কোন সময়ের:/ }).click();
  }
  const panel = page.getByRole("dialog");
  await panel.getByLabel("থেকে", { exact: true }).fill(from);
  await panel.getByLabel("পর্যন্ত", { exact: true }).fill(to);
  await panel.getByRole("button", { name: onPhone() ? "ফলাফল দেখুন" : "প্রয়োগ করুন" }).click();
}

/** One value of a filter such as a category, picked the same two ways. */
export async function pickChoice(page: Page, filter: string, option: string) {
  if (onPhone()) {
    await page.getByRole("button", { name: /^ফিল্টার/ }).click();
    const sheet = page.getByRole("dialog");
    await sheet.getByRole("group", { name: filter }).getByRole("button", { name: option, exact: true }).click();
    await sheet.getByRole("button", { name: "ফলাফল দেখুন" }).click();
  } else {
    await page.getByRole("button", { name: new RegExp(`^${filter}:`) }).click();
    await page.getByRole("dialog").getByRole("button", { name: option, exact: true }).click();
  }
}
