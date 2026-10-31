// The app's own code, kept so it opens quickly and even with no connection. Never the data: what comes
// from /api always comes live from the server, because stock must not be shown from an old copy.
const SHELL = "mojud-shell-1";
const ASSETS = "mojud-assets-1";
const MOST_ASSETS = 200;

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(SHELL)
      .then((cache) => cache.add("/"))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) =>
        Promise.all(names.filter((name) => name !== SHELL && name !== ASSETS).map((name) => caches.delete(name))),
      )
      .then(() => self.clients.claim()),
  );
});

/** The page itself is asked for first, so an update reaches everyone at once; the copy is for when there's no connection. */
async function page(request) {
  const cache = await caches.open(SHELL);
  try {
    const response = await fetch(request);
    if (response.ok) {
      await cache.put("/", response.clone());
    }
    return response;
  } catch (error) {
    const copy = await cache.match("/");
    if (copy) {
      return copy;
    }
    throw error;
  }
}

/** Built files have their content in their names, so a copy is always right. Older builds' files fall out in time. */
async function asset(request) {
  const cache = await caches.open(ASSETS);
  const copy = await cache.match(request);
  if (copy) {
    return copy;
  }
  const response = await fetch(request);
  if (response.ok) {
    await cache.put(request, response.clone());
    const kept = await cache.keys();
    await Promise.all(kept.slice(0, Math.max(0, kept.length - MOST_ASSETS)).map((old) => cache.delete(old)));
  }
  return response;
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin) {
    return;
  }
  // Slips, invoices and the gateway's returns are pages too, and they're the server's.
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/dev/") || url.pathname.startsWith("/healthz")) {
    return;
  }
  if (request.mode === "navigate") {
    event.respondWith(page(request));
  } else if (url.pathname.startsWith("/assets/")) {
    event.respondWith(asset(request));
  }
});
