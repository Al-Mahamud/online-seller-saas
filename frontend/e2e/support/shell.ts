import type { Page } from "@playwright/test";
import { test } from "./test";

/**
 * For now the laptop sidebar leaves out the notification link and the language switch; the phone's
 * top bar keeps both. Tests check those on the phone, and switch language through the account
 * page on a laptop.
 */
export const hasBellAndLanguageSwitch = () => test.info().project.name === "phone";

/** Switches the app's language: with the switch when there is one, else on the account page. */
export async function switchLanguage(page: Page, name: "English" | "বাংলা") {
  const toggle = page.getByRole("button", { name, exact: true }).first();
  if (hasBellAndLanguageSwitch()) {
    await toggle.click();
    return;
  }
  const back = new URL(page.url()).pathname + new URL(page.url()).search;
  await page.goto("/settings/account");
  await page.getByRole("button", { name, exact: true }).click();
  await page.goto(back);
}
