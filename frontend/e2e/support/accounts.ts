import type { APIRequestContext } from "@playwright/test";

/** A fresh Bangladeshi mobile number, so tests sharing one database never collide. */
export function newPhone(): string {
  return `017${String(Math.floor(Math.random() * 100_000_000)).padStart(8, "0")}`;
}

async function csrfToken(request: APIRequestContext): Promise<string> {
  await request.get("/api/v1/auth/csrf");
  const cookie = (await request.storageState()).cookies.find((c) => c.name === "XSRF-TOKEN");
  if (!cookie) {
    throw new Error("No XSRF-TOKEN cookie after GET /api/v1/auth/csrf");
  }
  return cookie.value;
}

/**
 * Signs up through the app's own origin, as the browser would: a sign-up code by SMS (read back from
 * the test backend's /dev/otp), then the account. The request context keeps the session cookie.
 */
export async function signUp(request: APIRequestContext, name = "Test Seller") {
  const phone = newPhone();
  const password = "test-password-1";
  const headers = { "X-XSRF-TOKEN": await csrfToken(request) };

  const sent = await request.post("/api/v1/auth/otp", { headers, data: { to: phone, purpose: "SIGNUP" } });
  if (sent.status() !== 202) {
    throw new Error(`POST /auth/otp answered ${sent.status()}: ${await sent.text()}`);
  }
  const otp = await request.get(`/dev/otp?to=${phone}`);
  const { code } = (await otp.json()) as { code: string };

  const created = await request.post("/api/v1/auth/signup", {
    headers,
    data: { name, phone, password, codeSentTo: "PHONE", code },
  });
  if (created.status() !== 201) {
    throw new Error(`POST /auth/signup answered ${created.status()}: ${await created.text()}`);
  }
  return { phone, password, name };
}
