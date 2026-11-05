import { signUp } from "./support/accounts";
import { expect, test } from "./support/test";

/** The app and the API share one origin (frontend-plan.md, "How the app talks to the API"). */
test("reaches the API on the app's own origin", async ({ request }) => {
  const options = await request.get("/api/v1/auth/options");

  expect(options.status()).toBe(200);
  expect(await options.json()).toHaveProperty("otpChannels");
});

test("gives every answer an ID, to find its lines in the server's log", async ({ request }) => {
  const answer = await request.get("/api/v1/me");
  const id = answer.headers()["x-request-id"];
  expect(id).toMatch(/^[0-9a-f]{12}$/);
  expect((await answer.json()).requestId).toBe(id);
});

test("signs up and stays signed in with the session cookie", async ({ request }) => {
  const account = await signUp(request);

  const me = await request.get("/api/v1/me");

  expect(me.status()).toBe(200);
  expect(await me.json()).toMatchObject({ name: account.name, phone: `+88${account.phone}` });
});

test("answers in the language asked for", async ({ request }) => {
  const english = await request.get("/api/v1/me", { headers: { "Accept-Language": "en" } });
  const bangla = await request.get("/api/v1/me", { headers: { "Accept-Language": "bn" } });

  expect(english.status()).toBe(401);
  expect((await english.json()).code).toBe("auth.required");
  expect((await bangla.json()).detail).not.toBe((await english.json()).detail);
});
