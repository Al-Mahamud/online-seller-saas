/**
 * The backend answers errors with RFC 9457 problem JSON: a machine-readable `code`
 * ("stock.insufficient"), a `detail` already translated for Accept-Language, and for invalid
 * input a list of `errors` ({field, message}). Some codes carry more, such as `metric` and `limit`
 * on plan.limit_reached.
 */
export interface FieldProblem {
  field: string;
  message: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
    readonly fields: FieldProblem[] = [],
    readonly retryAfterSeconds: number | null = null,
    readonly properties: Record<string, unknown> = {},
  ) {
    super(`${status} ${code}: ${detail}`);
    this.name = "ApiError";
  }

  static from(response: Response, body: unknown): ApiError {
    const problem = (typeof body === "object" && body !== null ? body : {}) as Record<string, unknown>;
    const fields = Array.isArray(problem.errors)
      ? problem.errors.filter(
          (item): item is FieldProblem => typeof item?.field === "string" && typeof item?.message === "string",
        )
      : [];
    const retryAfter = Number(response.headers.get("Retry-After"));
    return new ApiError(
      response.status,
      typeof problem.code === "string" ? problem.code : `http.${response.status}`,
      typeof problem.detail === "string" ? problem.detail : "",
      fields,
      Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : null,
      problem,
    );
  }
}

/** The request never got an answer: offline, or the server is down. */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super("Network request failed", { cause });
    this.name = "NetworkError";
  }
}

/**
 * What the app does about an error (frontend-plan.md, "How the app talks to the API"). Screens
 * show `fields` under their inputs; the rest is handled once for the whole app.
 */
export type ErrorAction =
  | { kind: "sign-in" }
  | { kind: "pick-business" }
  | { kind: "upgrade"; metric: string | null; limit: number | null }
  | { kind: "read-only"; suspended: boolean }
  | { kind: "rate-limited"; seconds: number | null }
  | { kind: "fields"; fields: FieldProblem[]; detail: string }
  | { kind: "network" }
  | { kind: "message"; detail: string | null };

export function errorAction(error: unknown): ErrorAction {
  if (error instanceof NetworkError) {
    return { kind: "network" };
  }
  if (!(error instanceof ApiError)) {
    return { kind: "message", detail: null };
  }
  switch (error.code) {
    case "auth.required":
      return { kind: "sign-in" };
    case "business.not_selected":
      return { kind: "pick-business" };
    case "plan.limit_reached":
      return {
        kind: "upgrade",
        metric: typeof error.properties.metric === "string" ? error.properties.metric : null,
        limit: typeof error.properties.limit === "number" ? error.properties.limit : null,
      };
    case "subscription.read_only":
      return { kind: "read-only", suspended: false };
    case "subscription.suspended":
      return { kind: "read-only", suspended: true };
    case "rate_limited":
      return { kind: "rate-limited", seconds: error.retryAfterSeconds };
  }
  if (error.fields.length > 0) {
    return { kind: "fields", fields: error.fields, detail: error.detail };
  }
  return { kind: "message", detail: error.detail || null };
}
