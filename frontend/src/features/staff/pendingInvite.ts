const STORAGE_KEY = "mojud.pendingInvite";
let inMemory: string | null = null;

/**
 * The invite token, from the link's fragment. Browsers never send a fragment to a server, and the
 * token must not reach one in a URL (Caddy logs them), so it stays out of the sign-in page's
 * `redirect`: it waits here while the person signs in or up.
 */
export function pendingInviteToken(): string | null {
  const fromLink = window.location.hash.slice(1);
  if (fromLink) {
    inMemory = fromLink;
    try {
      sessionStorage.setItem(STORAGE_KEY, fromLink);
    } catch {
      // Blocked storage: it still survives signing in, which doesn't reload the page.
    }
    return fromLink;
  }
  try {
    return sessionStorage.getItem(STORAGE_KEY) ?? inMemory;
  } catch {
    return inMemory;
  }
}

export function clearPendingInvite() {
  inMemory = null;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing was stored.
  }
}
