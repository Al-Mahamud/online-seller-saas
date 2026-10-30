import { type APIRequestContext, expect } from "@playwright/test";

/** A fresh Bangladeshi mobile number, so tests sharing one database never collide. */
export function newPhone(): string {
  return `017${String(Math.floor(Math.random() * 100_000_000)).padStart(8, "0")}`;
}

export function newEmail(): string {
  return `seller-${newPhone()}@example.com`;
}

export const PASSWORD = "test-password-1";

async function lastCode(request: APIRequestContext, to: string): Promise<string | null> {
  const answer = await request.get(`/dev/otp?to=${encodeURIComponent(to)}`);
  return answer.ok() ? ((await answer.json()) as { code: string }).code : null;
}

/**
 * The code sent to a phone or email, from the test backend's /dev/otp. Codes go out in the background,
 * so this waits for one; `not` is the code seen before, when a new one is expected.
 */
export async function readCode(request: APIRequestContext, to: string, not?: string | null): Promise<string> {
  let code: string | null = null;
  await expect
    .poll(async () => {
      code = await lastCode(request, to);
      return code !== null && code !== not;
    })
    .toBe(true);
  return code as unknown as string;
}

export { lastCode };

async function csrfToken(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  const cookie = (await request.storageState()).cookies.find((c) => c.name === "XSRF-TOKEN");
  if (!cookie) {
    throw new Error("No XSRF-TOKEN cookie after GET /api/v1/auth/csrf");
  }
  return cookie.value;
}

/**
 * Signs up through the app's own origin, as the browser would. Pass `page.request` to sign the page's
 * browser in: they share cookies. New accounts' language is the one asked for, Bangla by default.
 */
export async function signUp(request: APIRequestContext, { name = "Test Seller", language = "bn" } = {}) {
  const phone = newPhone();
  const headers = { "X-XSRF-TOKEN": await csrfToken(request), "Accept-Language": language };

  const sent = await request.post("/api/v1/auth/otp", { headers, data: { to: phone, purpose: "SIGNUP" } });
  expect(sent.status()).toBe(202);
  const code = await readCode(request, phone);

  const created = await request.post("/api/v1/auth/signup", {
    headers,
    data: { name, phone, password: PASSWORD, codeSentTo: "PHONE", code },
  });
  expect(created.status()).toBe(201);
  return { phone, password: PASSWORD, name };
}
