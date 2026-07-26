const scopeUrl = new URL(self.registration.scope);

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", (event) => {
  const requestUrl = new URL(event.request.url);
  if (
    event.request.method !== "GET" ||
    requestUrl.origin !== scopeUrl.origin ||
    !requestUrl.pathname.startsWith(scopeUrl.pathname)
  ) {
    return;
  }
  event.respondWith(
    fetch(event.request).catch(async () => {
      const cached = await caches.match(event.request);
      if (cached === undefined) {
        throw new Error(`offline resource is unavailable: ${requestUrl.pathname}`);
      }
      return cached;
    }),
  );
});
