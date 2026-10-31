import { readFileSync } from "node:fs";
import type { APIRequestContext, Page } from "@playwright/test";
import { newPhone, PASSWORD, signUp, signUpWithBusiness } from "./support/accounts";
import { expectAccessible } from "./support/axe";
import { expect, test } from "./support/test";
import { clearOfStepEnd, totp } from "./support/totp";

/** Every route the app has, from the router's own list: a new screen without a place here fails below. */
function allRoutes(): string[] {
  const source = readFileSync("src/routeTree.gen.ts", "utf8");
  const block = source.slice(source.indexOf("export interface FileRoutesByFullPath"));
  const list = block.slice(0, block.indexOf("}"));
  return [...new Set([...list.matchAll(/'([^']+)':/g)].map((match) => (match[1] as string).replace(/\/$/, "") || "/"))];
}

async function xsrf(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  return (await request.storageState()).cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
}

async function post(request: APIRequestContext, path: string, data?: unknown) {
  const answer = await request.post(path, { headers: { "X-XSRF-TOKEN": await xsrf(request) }, data });
  expect(answer.ok(), `${path}: ${await answer.text()}`).toBe(true);
  return (await answer.json().catch(() => ({}))) as Record<string, never>;
}

/** A business with something on every screen, so the lists and the details are really there. */
async function seed(request: APIRequestContext) {
  const product = await post(request, "/api/v1/products", {
    name: "কুর্তি",
    price: 150000,
    costPrice: 90000,
    openingStock: 6,
  });
  const variantId = (product.variants as unknown as { id: string }[])[0]?.id;
  const customer = await post(request, "/api/v1/customers", {
    name: "রহিমা",
    phone: newPhone(),
    address: "বাসা ৪",
    districtId: 47,
  });
  const order = await post(request, "/api/v1/orders", {
    customerId: customer.id,
    items: [{ variantId, quantity: 2 }],
    deliveryCharge: 6000,
  });
  await post(request, `/api/v1/orders/${order.id}/confirm`);
  const shipped = await post(request, "/api/v1/orders", {
    customerId: customer.id,
    items: [{ variantId, quantity: 1 }],
    deliveryCharge: 6000,
  });
  await post(request, `/api/v1/orders/${shipped.id}/confirm`);
  await post(request, `/api/v1/orders/${shipped.id}/ship`, { courier: "Pathao" });
  await post(request, `/api/v1/orders/${shipped.id}/deliver`);
  const invoice = await post(request, "/api/v1/billing/checkout", {
    planCode: "STARTER",
    months: 1,
    payBy: "BANK_TRANSFER",
  });
  return {
    productId: product.id as unknown as string,
    customerId: customer.id as unknown as string,
    orderId: order.id as unknown as string,
    invoiceId: invoice.invoiceId as unknown as string,
  };
}

async function check(page: Page, seen: Set<string>, route: string, path = route) {
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1 }).first(), `${path} has a heading`).toBeVisible();
  await expectAccessible(page).catch((error: Error) => {
    throw new Error(`${path}: ${error.message}`);
  });
  seen.add(route);
}

for (const language of ["bn", "en"] as const) {
  test(`every screen has no accessibility problems, in ${language}`, async ({ page, browser }) => {
    test.setTimeout(240_000);
    const seen = new Set<string>();
    const signedOut = await browser.newContext({
      baseURL: test.info().project.use.baseURL,
      viewport: test.info().project.use.viewport,
      serviceWorkers: "block",
    });
    await signedOut.addInitScript((chosen) => localStorage.setItem("mojud.language", chosen), language);
    const visitor = await signedOut.newPage();
    for (const route of ["/sign-in", "/sign-up", "/forgot-password", "/forgot-user-id"]) {
      await check(visitor, seen, route);
    }
    await check(visitor, seen, "/invites", "/invites#not-a-real-token");
    await signedOut.close();

    const newcomer = await browser.newContext({
      baseURL: test.info().project.use.baseURL,
      viewport: test.info().project.use.viewport,
      serviceWorkers: "block",
    });
    const first = await newcomer.newPage();
    await signUp(first.request, { language });
    await check(first, seen, "/welcome");
    await newcomer.close();

    const account = await signUpWithBusiness(page.request, { language });
    const ids = await seed(page.request);
    expect((await page.request.post(`/dev/platform-admin?phone=${account.phone}`)).status()).toBe(204);
    await post(page.request, "/api/v1/auth/logout");
    await post(page.request, "/api/v1/auth/login", { userId: account.userId, password: PASSWORD });
    const { secret } = (await post(page.request, "/api/v1/me/two-factor/setup", { password: PASSWORD })) as unknown as {
      secret: string;
    };
    await clearOfStepEnd();
    await post(page.request, "/api/v1/me/two-factor/enable", { code: totp(secret, -1) });
    const business = (await (await page.request.get("/api/v1/business")).json()) as { id: string };

    const withParams: Record<string, string> = {
      "/products/$productId": `/products/${ids.productId}`,
      "/customers/$customerId": `/customers/${ids.customerId}`,
      "/orders/$orderId": `/orders/${ids.orderId}`,
      "/orders/$orderId/edit": `/orders/${ids.orderId}/edit`,
      "/billing/invoices/$invoiceId": `/billing/invoices/${ids.invoiceId}`,
      "/billing/return": `/billing/return?result=cancel&invoice=${ids.invoiceId}`,
      "/admin/businesses/$businessId": `/admin/businesses/${business.id}`,
    };
    for (const route of allRoutes()) {
      if (!seen.has(route)) {
        await check(page, seen, route, withParams[route] ?? route);
      }
    }
    expect([...seen].sort()).toEqual(allRoutes().sort());
  });
}
