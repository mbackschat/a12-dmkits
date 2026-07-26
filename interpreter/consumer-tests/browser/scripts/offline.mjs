import {
  launchChrome,
  openCheckedPage,
  PAGES_BASE,
  withDeadline,
  withStaticServer,
} from "./harness.mjs";

const failureCases = [
  {
    reason: "integrity-mismatch",
    expectedDiagnostic: "MODEL_SOURCE_INTEGRITY_MISMATCH",
  },
  {reason: "source-too-large", expectedDiagnostic: "source-too-large"},
  {reason: "cancelled", expectedDiagnostic: "cancelled"},
];

await withStaticServer(async (url) => {
  const browser = await launchChrome();
  let primaryFailure;
  try {
    const context = await browser.newContext();
    const unsafeRequests = [];
    const showcaseOrigin = new URL(url).origin;
    context.on("request", (request) => {
      const headers = request.headers();
      if (
        new URL(request.url()).origin !== showcaseOrigin ||
        headers.authorization !== undefined ||
        headers.cookie !== undefined ||
        request.url().includes("/Users/") ||
        request.url().includes("registry.internal.invalid")
      ) {
        unsafeRequests.push(request.url());
      }
    });
    const page = await openCheckedPage(context, url);
    await withDeadline(
      "offline readiness",
      page.locator("#status[data-offline-ready=true]").waitFor(),
    );

    const revision = await page.locator("#workspace-summary")
      .getAttribute("data-manifest-revision");
    if (!revision) {
      throw new Error("showcase did not expose its manifest revision");
    }

    await page.selectOption("#workspace-mode", "provider");
    await page.click(".failure-controls summary");
    for (const {reason, expectedDiagnostic} of failureCases) {
      await page.selectOption("#provider-failure", reason);
      await page.locator("#result").evaluate((element) => {
        delete element.dataset.operation;
      });
      await page.click("#prepare");
      await withDeadline(
        `${reason} failure`,
        page.locator("#result[data-operation=error]").waitFor(),
      );
      const output = await page.locator("#result").textContent();
      if (!output?.includes(expectedDiagnostic)) {
        throw new Error(
          `${reason} failure did not remain structured: ${output}`,
        );
      }
      if (
        output.includes("http://") ||
        output.includes("https://") ||
        output.includes("/Users/")
      ) {
        throw new Error(`${reason} failure exposed an acquisition location`);
      }
    }

    await page.selectOption("#provider-failure", "none");
    await page.selectOption("#scenario", "include-typedef");
    await page.selectOption("#workspace-mode", "provider");
    await page.click("#prepare");
    await page.click("#validate");
    const onlineResult = await page.locator(
      "#result[data-operation=validate-full]",
    ).textContent();
    if (
      !onlineResult?.includes("NEGATIVE_SCORE") ||
      !onlineResult.includes("stringZuLang")
    ) {
      throw new Error(`online manifest workspace failed: ${onlineResult}`);
    }

    await page.evaluate(async () => {
      await caches.open("a12-interpreter-showcase-models:stale");
      await caches.open("a12-interpreter-showcase-shell:stale");
    });
    await page.reload({waitUntil: "networkidle"});
    await withDeadline(
      "revision cache cleanup",
      page.locator("#status[data-offline-ready=true]").waitFor(),
    );
    const cacheNames = await page.evaluate(() => caches.keys());
    if (
      cacheNames.some((name) => name.endsWith(":stale")) ||
      !cacheNames.includes(`a12-interpreter-showcase-models:${revision}`) ||
      !cacheNames.includes(`a12-interpreter-showcase-shell:${revision}`)
    ) {
      throw new Error(
        `showcase cache revision isolation failed: ${JSON.stringify(cacheNames)}`,
      );
    }

    await context.setOffline(true);
    await page.reload({waitUntil: "domcontentloaded"});
    await withDeadline(
      "offline showcase reload",
      page.locator("#status[data-offline-ready=true]").waitFor(),
    );
    await page.selectOption("#scenario", "include-typedef");
    await page.selectOption("#workspace-mode", "provider");
    await page.click("#prepare");
    await page.click("#validate");
    const offlineResult = await page.locator(
      "#result[data-operation=validate-full]",
    ).textContent();
    if (
      !offlineResult?.includes("NEGATIVE_SCORE") ||
      !offlineResult.includes("stringZuLang")
    ) {
      throw new Error(`offline manifest workspace failed: ${offlineResult}`);
    }
    if (new URL(page.url()).pathname !== PAGES_BASE) {
      throw new Error(`offline page escaped the Pages base: ${page.url()}`);
    }
    if (unsafeRequests.length > 0) {
      throw new Error(
        `showcase emitted unsafe requests: ${JSON.stringify(unsafeRequests)}`,
      );
    }
    await context.close();
  } catch (error) {
    primaryFailure = error;
    throw error;
  } finally {
    try {
      await withDeadline("Chrome shutdown", browser.close());
    } catch (closeError) {
      if (primaryFailure === undefined) {
        throw closeError;
      }
    }
  }
});
