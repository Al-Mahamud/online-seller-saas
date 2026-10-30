import createClient, { type Middleware } from "openapi-fetch";
import { currentLanguage } from "@/i18n";
import { ApiError, NetworkError } from "./errors";
import type { paths } from "./schema";

/**
 * The typed API client, generated from the backend's own API description (src/api/openapi.json,
 * kept current by the backend's OpenApiSnapshotTests; `npm run api:types` regenerates schema.d.ts).
 * A path, parameter or field the API doesn't have is a type error.
 *
 *   const me = await call(api.GET("/api/v1/me"));
 */
export type Api = ReturnType<typeof createApi>;

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

let expectedBusiness: string | null = null;

/**
 * Writes name the business on screen, and the server refuses them if the session has moved to another
 * (a member removed from it moves by themself): the change must not land in the wrong business.
 */
export function setExpectedBusiness(businessId: string | null) {
  expectedBusiness = businessId;
}
const CSRF_COOKIE = "XSRF-TOKEN";

function cookie(name: string): string | null {
  const entry = document.cookie.split("; ").find((part) => part.startsWith(`${name}=`));
  return entry ? decodeURIComponent(entry.slice(name.length + 1)) : null;
}

/**
 * Every request carries the language on screen, so the server's messages come back in it, and every
 * write carries the CSRF token: the XSRF-TOKEN cookie, fetched once from GET /auth/csrf when the
 * browser has none (at first, and after signing in or out, which replace it).
 */
export function createApi(fetchImpl: typeof fetch = (...args) => fetch(...args)) {
  let pendingToken: Promise<unknown> | null = null;

  async function csrfToken(): Promise<string | null> {
    if (!cookie(CSRF_COOKIE)) {
      pendingToken ??= fetchImpl(new Request(new URL("/api/v1/auth/csrf", location.origin))).finally(() => {
        pendingToken = null;
      });
      await pendingToken;
    }
    return cookie(CSRF_COOKIE);
  }

  const headers: Middleware = {
    async onRequest({ request }) {
      request.headers.set("Accept-Language", currentLanguage());
      if (UNSAFE_METHODS.has(request.method)) {
        const token = await csrfToken();
        if (token) {
          request.headers.set("X-XSRF-TOKEN", token);
        }
        if (expectedBusiness) {
          request.headers.set("X-Business-Id", expectedBusiness);
        }
      }
      return request;
    },
  };

  const client = createClient<paths>({ baseUrl: location.origin, fetch: fetchImpl });
  client.use(headers);
  return client;
}

export const api = createApi();

type Result<T> = { data?: T; error?: unknown; response: Response };

/** The answer's body, or an ApiError (problem JSON) or NetworkError (no answer) thrown. */
export async function call<T>(request: Promise<Result<T>>): Promise<T> {
  let result: Result<T>;
  try {
    result = await request;
  } catch (cause) {
    throw new NetworkError(cause);
  }
  if (!result.response.ok) {
    throw ApiError.from(result.response, result.error);
  }
  return result.data as T;
}

/**
 * A key for one intended change (an order, a payment, a stock change), sent as Idempotency-Key so
 * that a double tap or a retry on a bad connection never makes two. Make it when the person starts
 * the action and reuse it for every retry of that action.
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
