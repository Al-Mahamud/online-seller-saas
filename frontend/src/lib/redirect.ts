/**
 * The page to return to after signing in, from the address bar. Only a path on this site: "//evil.example"
 * and "/\evil.example" are read by browsers as another site, so anything like them is dropped.
 */
export function safeRedirect(target: unknown): string | undefined {
  return typeof target === "string" && /^\/(?![/\\])/.test(target) ? target : undefined;
}

/** The search of the sign-in and sign-up pages. */
export function validateRedirectSearch(search: Record<string, unknown>): { redirect?: string } {
  const redirect = safeRedirect(search.redirect);
  return redirect ? { redirect } : {};
}
