import type { CDPSession, Page } from "@playwright/test";
import { signUpWithBusiness } from "../support/accounts";
import { expect, test } from "../support/test";

/**
 * A first visit on the target (frontend-plan.md, "Performance budget"): a ৳12,000 Android phone on a
 * busy 4G connection, usable within 3 seconds. Run by hand with `npm run perf`, against the built app
 * served compressed; too dependent on the machine for CI.
 */
const BUDGET_MS = 3_000;

async function slowDown(page: Page): Promise<CDPSession> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Network.enable");
  await cdp.send("Network.setCacheDisabled", { cacheDisabled: true });
  await cdp.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: 150,
    downloadThroughput: (1.6 * 1024 * 1024) / 8,
    uploadThroughput: (750 * 1024) / 8,
  });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  return cdp;
}

/** Opens the page cold, and says how long until `usable` is on screen. */
async function timed(page: Page, name: string, path: string, usable: () => Promise<void>) {
  await page.addInitScript(() => {
    (window as unknown as { lcp: number }).lcp = 0;
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        (window as unknown as { lcp: number }).lcp = entry.startTime;
      }
    }).observe({ type: "largest-contentful-paint", buffered: true });
  });
  await slowDown(page);
  const started = Date.now();
  await page.goto(path, { waitUntil: "commit" });
  await usable();
  const ms = Date.now() - started;
  await page.waitForLoadState("load");
  const figures = await page.evaluate(() => {
    const resources = performance.getEntriesByType("resource") as PerformanceResourceTiming[];
    const kb = (kind: (name: string) => boolean) =>
      Math.round(resources.filter((one) => kind(one.name)).reduce((sum, one) => sum + one.encodedBodySize, 0) / 1024);
    return {
      lcp: Math.round((window as unknown as { lcp: number }).lcp),
      js: kb((url) => url.endsWith(".js")),
      css: kb((url) => url.endsWith(".css")),
      fonts: kb((url) => url.endsWith(".woff2")),
      fontFiles: resources.filter((one) => one.name.endsWith(".woff2")).length,
    };
  });
  console.log(
    `${name.padEnd(14)} usable ${String(ms).padStart(5)} ms · LCP ${String(figures.lcp).padStart(5)} ms · JS ${figures.js} KB · CSS ${figures.css} KB · fonts ${figures.fonts} KB in ${figures.fontFiles}`,
  );
  test.info().annotations.push({ type: name, description: `${ms} ms` });
  return ms;
}

/** Everything cached, as a seller opening the app again finds it. */
async function again(page: Page, name: string, path: string, usable: () => Promise<void>) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Network.setCacheDisabled", { cacheDisabled: false });
  await page.goto(path, { waitUntil: "commit" });
  await usable();
  await page.waitForLoadState("load");
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload({ waitUntil: "commit" }).catch(() => undefined);
  await usable();
  await page.waitForTimeout(300);
  const started = Date.now();
  await page.goto(path, { waitUntil: "commit" });
  await usable();
  const ms = Date.now() - started;
  console.log(`${name.padEnd(14)} return ${String(ms).padStart(5)} ms`);
  return ms;
}

test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

const SIGN_IN_READY = (page: Page) => () =>
  expect(page.getByRole("button", { name: "লগ ইন", exact: true })).toBeEnabled({ timeout: 30_000 });

/** A new visitor's first screen is the budget's own case. */
test("sign-in, cold", async ({ page }) => {
  const ms = await timed(page, "sign-in", "/sign-in", SIGN_IN_READY(page));
  expect(ms).toBeLessThan(BUDGET_MS);
});

const SCREENS = [
  ["today", "/", (page: Page) => page.getByText("বিক্রি", { exact: true }).first()],
  ["orders", "/orders", (page: Page) => page.getByRole("searchbox", { name: "অর্ডার খুঁজুন" })],
  ["quick order", "/orders/new", (page: Page) => page.getByLabel("ফোন নম্বর", { exact: true })],
  ["products", "/products", (page: Page) => page.getByRole("heading", { level: 1, name: "পণ্য" })],
] as const;

for (const [name, path, ready] of SCREENS) {
  /** Signed in with nothing cached, which is rare: reported, and held to a looser line. */
  test(`${name}, cold, signed in`, async ({ page }) => {
    await signUpWithBusiness(page.request);
    const ms = await timed(page, name, path, () => expect(ready(page)).toBeVisible({ timeout: 30_000 }));
    expect(ms).toBeLessThan(BUDGET_MS + 500);
  });

  /** The everyday case: the app's own code is on the phone. */
  test(`${name}, returning`, async ({ page }) => {
    await signUpWithBusiness(page.request);
    await slowDown(page);
    const ms = await again(page, name, path, () => expect(ready(page)).toBeVisible({ timeout: 30_000 }));
    expect(ms).toBeLessThan(BUDGET_MS);
  });
}
