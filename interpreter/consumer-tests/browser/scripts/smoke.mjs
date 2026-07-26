import {
  launchChrome,
  openCheckedPage,
  PAGES_BASE,
  withDeadline,
  withStaticServer,
} from "./harness.mjs";

await withStaticServer(async (url) => {
  const browser = await launchChrome();
  try {
    const page = await openCheckedPage(browser, url);
    const qualification = page.locator(
      "#status[data-state=passed][data-corpus-cases='33'][data-workspace='passed'][data-host='passed']",
    );
    if (await qualification.count() !== 1) {
      throw new Error(
        `browser qualification failed: ${
          await page.locator("#status").textContent()
        }`,
      );
    }
    if (new URL(page.url()).pathname !== PAGES_BASE) {
      throw new Error(`browser escaped the Pages base path: ${page.url()}`);
    }
    console.log(await page.locator("#status").textContent());

    const performancePage = await openCheckedPage(
      browser,
      `${url}?mode=performance`,
    );
    const performanceStatus = performancePage.locator(
      "#status[data-state=passed][data-performance=passed]",
    );
    if (await performanceStatus.count() !== 1) {
      throw new Error(
        `browser performance scenario failed: ${
          await performancePage.locator("#status").textContent()
        }`,
      );
    }
    console.log(await performancePage.locator("#status").textContent());
  } finally {
    await withDeadline("Chrome shutdown", browser.close());
  }
});
