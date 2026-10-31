import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";

// What anyone sees before signing in. Needs no test backend, so the deploy drill runs it too, against
// the real server behind Caddy.

test("sends someone signed out to sign-in, keeping the page", async ({ page }) => {
  await page.goto("/products");

  await expect(page).toHaveURL(/\/sign-in\?redirect=%2Fproducts$/);
  await expect(page.getByRole("heading", { level: 1, name: "আবার স্বাগতম" })).toBeVisible();
  await expect(page).toHaveTitle("মজুদ");
  await expect(page.locator("html")).toHaveAttribute("lang", "bn");
});

test("sign-in is accessible in both languages", async ({ page }) => {
  await page.goto("/sign-in");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "English" }).click();

  await expect(page.getByRole("heading", { level: 1, name: "Welcome back" })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expectAccessible(page);
});

test("the sign-up and forgot-password pages open and are accessible", async ({ page }) => {
  await page.goto("/sign-in");

  await page.getByRole("link", { name: "নতুন অ্যাকাউন্ট খুলুন" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "নতুন অ্যাকাউন্ট" })).toBeVisible();
  await expectAccessible(page);

  await page.goto("/forgot-password");
  await expect(page.getByRole("heading", { level: 1, name: "পাসওয়ার্ড ভুলে গেছেন?" })).toBeVisible();
  await expectAccessible(page);
});

test("says when a page does not exist", async ({ page }) => {
  await page.goto("/sign-in/nowhere");

  await expect(page.getByRole("heading", { level: 1, name: "এই পাতাটি পাওয়া যায়নি।" })).toBeVisible();
});

test("fits the screen without sideways scrolling", async ({ page }) => {
  await page.goto("/sign-in");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test("can be installed: the manifest, its icons and the service worker are served", async ({ request }) => {
  const answer = await request.get("/manifest.webmanifest");
  expect(answer.ok()).toBe(true);
  const manifest = (await answer.json()) as {
    name: string;
    display: string;
    icons: { src: string; sizes: string; purpose: string }[];
  };
  expect(manifest).toMatchObject({ name: "মজুদ", display: "standalone" });
  expect(manifest.icons.map((icon) => icon.sizes)).toEqual(expect.arrayContaining(["192x192", "512x512"]));
  expect(manifest.icons.some((icon) => icon.purpose === "maskable")).toBe(true);
  for (const icon of manifest.icons) {
    const image = await request.get(icon.src);
    expect(image.ok(), icon.src).toBe(true);
    expect(image.headers()["content-type"]).toContain("image/png");
  }
  const worker = await request.get("/sw.js");
  expect(worker.ok()).toBe(true);
  expect(worker.headers()["content-type"]).toContain("javascript");
  // Workers must update as soon as there's a new one.
  expect(worker.headers()["cache-control"] ?? "no-cache").not.toContain("immutable");
});

test.describe("with its service worker", () => {
  // The deploy drill's Caddy uses a local certificate, which browsers won't take a service worker over.
  test.skip(!!process.env.E2E_BASE_URL, "needs a trusted origin");
  test.use({ serviceWorkers: "allow", expectsNetworkErrors: true });

  test("opens with no connection and says so; the server's answers are never kept", async ({ page, context }) => {
    await page.goto("/sign-in");
    await expect(page.getByRole("heading", { level: 1, name: "আবার স্বাগতম" })).toBeVisible();
    await page.evaluate(async () => {
      await navigator.serviceWorker.ready;
    });
    // Loaded under the worker's control, so it has seen the code.
    await page.reload();
    await expect(page.getByRole("heading", { level: 1, name: "আবার স্বাগতম" })).toBeVisible();
    await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);

    const kept = await page.evaluate(async () => {
      const urls: string[] = [];
      for (const name of await caches.keys()) {
        const cache = await caches.open(name);
        urls.push(...(await cache.keys()).map((request) => new URL(request.url).pathname));
      }
      return urls;
    });
    expect(kept.length).toBeGreaterThan(1);
    expect(kept.filter((path) => path.startsWith("/api"))).toEqual([]);

    await context.setOffline(true);
    await page.goto("/orders");
    await expect(page.getByRole("alert")).toContainText("সার্ভারে পৌঁছানো যাচ্ছে না");
    await expect(page.getByRole("button", { name: "আবার চেষ্টা করুন" })).toBeVisible();
    await context.setOffline(false);
  });
});
