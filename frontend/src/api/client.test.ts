import { afterEach, describe, expect, it, vi } from "vitest";
import { setLanguage } from "@/i18n";
import { call, createApi } from "./client";
import { ApiError, errorAction, NetworkError } from "./errors";

function clearCookies() {
  for (const entry of document.cookie.split("; ")) {
    const name = entry.split("=")[0];
    if (name) {
      // biome-ignore lint/suspicious/noDocumentCookie: jsdom has no Cookie Store API; the client reads document.cookie.
      document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
    }
  }
}

/** The request a mocked fetch received in call `index`. */
function requestAt(fetchImpl: { mock: { calls: unknown[][] } }, index: number): Request {
  const request = fetchImpl.mock.calls[index]?.[0];
  if (!(request instanceof Request)) {
    throw new Error(`No request ${index}`);
  }
  return request;
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

afterEach(async () => {
  clearCookies();
  await setLanguage("bn");
});

describe("api client", () => {
  it("sends the language on screen", async () => {
    const fetchImpl = vi.fn(async (_request: Request) => json({ status: "ok" }));
    const api = createApi(fetchImpl as typeof fetch);
    await setLanguage("en");

    await api.GET("/api/v1/plans");

    const request = requestAt(fetchImpl, 0);
    expect(request.headers.get("Accept-Language")).toBe("en");
    expect(request.headers.get("X-XSRF-TOKEN")).toBeNull();
  });

  it("fetches a CSRF token before the first write and sends it", async () => {
    const fetchImpl = vi.fn(async (request: Request) => {
      if (request.url.endsWith("/api/v1/auth/csrf")) {
        // biome-ignore lint/suspicious/noDocumentCookie: stands in for the server's Set-Cookie.
        document.cookie = "XSRF-TOKEN=token-1; path=/";
        return new Response(null, { status: 204 });
      }
      return json({}, 202);
    });
    const api = createApi(fetchImpl as typeof fetch);

    await api.POST("/api/v1/auth/otp", { body: { to: "01712345678", purpose: "SIGNUP" } });
    await api.POST("/api/v1/auth/otp", { body: { to: "01712345678", purpose: "SIGNUP" } });

    const urls = fetchImpl.mock.calls.map((_call, index) => new URL(requestAt(fetchImpl, index).url).pathname);
    expect(urls).toEqual(["/api/v1/auth/csrf", "/api/v1/auth/otp", "/api/v1/auth/otp"]);
    expect(requestAt(fetchImpl, 1).headers.get("X-XSRF-TOKEN")).toBe("token-1");
  });
});

describe("call", () => {
  it("returns the body of a successful answer", async () => {
    const api = createApi((async () => json([{ code: "FREE" }])) as unknown as typeof fetch);

    await expect(call(api.GET("/api/v1/plans"))).resolves.toEqual([{ code: "FREE" }]);
  });

  it("throws the problem as an ApiError", async () => {
    const problem = {
      code: "validation.failed",
      detail: "Some fields are invalid.",
      errors: [{ field: "name", message: "Required." }],
    };
    const api = createApi((async () => json(problem, 400)) as unknown as typeof fetch);

    const error = await call(api.GET("/api/v1/plans")).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(errorAction(error)).toEqual({
      kind: "fields",
      fields: [{ field: "name", message: "Required." }],
      detail: "Some fields are invalid.",
    });
  });

  it("throws a NetworkError when there is no answer", async () => {
    const api = createApi((async () => {
      throw new TypeError("Failed to fetch");
    }) as unknown as typeof fetch);

    await expect(call(api.GET("/api/v1/plans"))).rejects.toBeInstanceOf(NetworkError);
  });
});

describe("errorAction", () => {
  const response = (status: number, headers: Record<string, string> = {}) => new Response(null, { status, headers });

  it("sends a signed-out person to sign in", () => {
    expect(errorAction(ApiError.from(response(401), { code: "auth.required" }))).toEqual({ kind: "sign-in" });
  });

  it("asks for a business when none is selected", () => {
    expect(errorAction(ApiError.from(response(409), { code: "business.not_selected" }))).toEqual({
      kind: "pick-business",
    });
  });

  it("offers an upgrade at a plan limit", () => {
    const error = ApiError.from(response(402), { code: "plan.limit_reached", metric: "products", limit: 50 });
    expect(errorAction(error)).toEqual({ kind: "upgrade", metric: "products", limit: 50 });
  });

  it("says when the business is read-only or suspended", () => {
    expect(errorAction(ApiError.from(response(402), { code: "subscription.read_only" }))).toEqual({
      kind: "read-only",
      suspended: false,
    });
    expect(errorAction(ApiError.from(response(402), { code: "subscription.suspended" }))).toEqual({
      kind: "read-only",
      suspended: true,
    });
  });

  it("reads how long to wait from Retry-After", () => {
    const error = ApiError.from(response(429, { "Retry-After": "42" }), { code: "rate_limited" });
    expect(errorAction(error)).toEqual({ kind: "rate-limited", seconds: 42 });
  });

  it("shows the server's message otherwise", () => {
    const error = ApiError.from(response(409), { code: "stock.insufficient", detail: "Not enough stock." });
    expect(errorAction(error)).toEqual({ kind: "message", detail: "Not enough stock." });
  });
});
