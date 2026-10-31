/**
 * The page to return to after signing in, from the address bar. Only a path on this site: "//evil.example"
 * and "/\evil.example" are read by browsers as another site, so anything like them is dropped.
 */
export function safeRedirect(target: unknown): string | undefined {
  return typeof target === "string" && /^\/(?![/\\])/.test(target) ? target : undefined;
}

export interface SignInSearch {
  redirect?: string;
  /** Filled in for someone who just recovered it or changed their password. */
  userId?: string;
  notice?: "password-changed";
}

/** The search of the sign-in page. */
export function validateSignInSearch(search: Record<string, unknown>): SignInSearch {
  return {
    ...validateRedirectSearch(search),
    ...(typeof search.userId === "string" ? { userId: search.userId } : {}),
    ...(search.notice === "password-changed" ? { notice: "password-changed" as const } : {}),
  };
}

/** The search of the sign-up page. */
export function validateRedirectSearch(search: Record<string, unknown>): { redirect?: string } {
  const redirect = safeRedirect(search.redirect);
  return redirect ? { redirect } : {};
}
