const serviceWorker = self as unknown as ServiceWorkerGlobalScope;
const scopeUrl = new URL(serviceWorker.registration.scope);

serviceWorker.addEventListener("install", () => serviceWorker.skipWaiting());
serviceWorker.addEventListener(
  "activate",
  (event) => event.waitUntil(serviceWorker.clients.claim()),
);
serviceWorker.addEventListener("fetch", (event) => {
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
