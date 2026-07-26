import {resolve} from "node:path";

import {
  launchChrome,
  openCheckedPage,
  PAGES_BASE,
  withDeadline,
  withStaticServer,
} from "./harness.mjs";

await withStaticServer(async (url) => {
  const browser = await launchChrome();
  let primaryFailure;
  try {
    const page = await openCheckedPage(browser, `${url}?mode=qualification`);
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

    const showcase = await openCheckedPage(browser, url);
    const version = showcase.locator(
      "#package-version[data-package='@mbackschat/a12-interpreter']",
    );
    if (await version.textContent() !== "0.9.1") {
      throw new Error(
        `showcase package version mismatch: ${await version.textContent()}`,
      );
    }
    const assertOutput = async (operation, expected) => {
      const output = showcase.locator(
        `#result[data-operation='${operation}']`,
      );
      await withDeadline(`${operation} result`, output.waitFor());
      const text = await output.textContent();
      for (const fragment of expected) {
        if (!text?.includes(fragment)) {
          throw new Error(
            `${operation} output is missing ${JSON.stringify(fragment)}: ${text}`,
          );
        }
      }
    };

    await showcase.selectOption("#scenario", "clean");
    await showcase.click("#validate");
    await assertOutput("validate-full", ['"findings": []']);

    await showcase.locator("#document-upload").setInputFiles({
      name: "permit-input.json",
      mimeType: "application/json",
      buffer: Buffer.from(JSON.stringify({
        version: 1,
        groups: [{path: "/Permit", coordinates: [1]}],
        fields: [
          {
            path: "/Permit/ApplicationNo",
            coordinates: [1, 1],
            raw: "P-UPLOAD",
          },
          {
            path: "/Permit/RequestedArea",
            coordinates: [1, 1],
            raw: "120",
          },
        ],
      })),
    });
    await showcase.locator(
      "#status[data-state=passed]",
    ).filter({hasText: "Placement document loaded"}).waitFor();
    await showcase.click("#validate");
    await assertOutput("validate-full", ["AREA_NEEDS_NOTE"]);

    await showcase.selectOption("#scenario", "firing");
    await showcase.selectOption("#validation-scope", "relevant");
    await showcase.click("#validate");
    await assertOutput("validate-relevant", ["AREA_NEEDS_NOTE"]);
    await showcase.selectOption("#validation-scope", "full");

    await showcase.selectOption("#scenario", "formal-invalid");
    await showcase.click("#validate");
    await assertOutput("validate-full", ["zahlHatUngueltigeZeichen"]);

    await showcase.selectOption("#scenario", "computation");
    await showcase.click("#compute");
    await assertOutput("compute", ["/Permit/ReviewedArea", '"value": "125"']);

    await showcase.selectOption("#workspace-mode", "file-map");
    await showcase.selectOption("#scenario", "include-typedef");
    await showcase.locator("#workspace-folder-upload").setInputFiles(
      resolve("dist/workspace"),
    );
    await showcase.locator(
      "#status[data-state=passed]",
    ).filter({hasText: "Workspace files loaded"}).waitFor();
    await showcase.selectOption("#entry-model-select", "permit-single");
    await showcase.click("#prepare");
    await showcase.click("#validate");
    await assertOutput(
      "validate-full",
      ["NEGATIVE_SCORE", "stringZuLang"],
    );
    const finiteFingerprint = await showcase.locator(
      "#workspace-summary",
    ).getAttribute("data-fingerprint");

    await showcase.selectOption("#workspace-mode", "provider");
    await showcase.click("#prepare");
    await showcase.click("#validate");
    await assertOutput(
      "validate-full",
      ["NEGATIVE_SCORE", "stringZuLang"],
    );
    const providerSummary = showcase.locator(
      "#workspace-summary[data-request-count='4']",
    );
    if (await providerSummary.count() !== 1) {
      throw new Error(
        `provider workspace did not load one discovered closure: ${
          await showcase.locator("#workspace-summary").textContent()
        }`,
      );
    }
    if (
      await providerSummary.getAttribute("data-fingerprint") !==
        finiteFingerprint
    ) {
      throw new Error("finite and provider showcase workspaces differ");
    }

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
