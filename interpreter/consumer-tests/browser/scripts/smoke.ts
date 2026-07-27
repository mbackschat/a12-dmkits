import {resolve} from "node:path";

import {
  launchChrome,
  openCheckedPage,
  PAGES_BASE,
  withDeadline,
  withStaticServer,
} from "./harness.js";

await withStaticServer(async (url) => {
  const browser = await launchChrome();
  let primaryFailure: unknown;
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

    const showcase = await openCheckedPage(
      browser,
      `${url}?skip-live-performance=1`,
    );
    const version = showcase.locator(
      "#package-version[data-package='@mbackschat/a12-interpreter']",
    );
    if (await version.textContent() !== "0.9.1") {
      throw new Error(
        `showcase package version mismatch: ${await version.textContent()}`,
      );
    }
    const bundleSize = showcase.locator(
      "#bundle-size[data-gzip-bytes][data-uncompressed-bytes]",
    );
    if (await bundleSize.count() !== 1) {
      throw new Error("showcase does not expose measured bundle sizes");
    }
    const gzipBytes = Number(await bundleSize.getAttribute("data-gzip-bytes"));
    const uncompressedBytes = Number(
      await bundleSize.getAttribute("data-uncompressed-bytes"),
    );
    if (
      !Number.isSafeInteger(gzipBytes) ||
      !Number.isSafeInteger(uncompressedBytes) ||
      gzipBytes <= 0 ||
      gzipBytes >= uncompressedBytes
    ) {
      throw new Error(
        `showcase bundle sizes are invalid: ${gzipBytes}/${uncompressedBytes}`,
      );
    }
    const bundleFact = await showcase.locator("#bundle-size").locator("..")
      .textContent();
    if (!bundleFact?.includes("interpreter JS bundle") ||
      bundleFact.includes("entry")) {
      throw new Error(`showcase bundle label uses build jargon: ${bundleFact}`);
    }
    if (
      await showcase.locator("a[href*='mgm-tp.com']").count() !== 0
    ) {
      throw new Error(
        "independent showcase links to an unrelated operator policy",
      );
    }
    const heroDetailMarkers = showcase.locator(
      ".hero-details .detail-marker",
    );
    const heroDetailMarkerText = await heroDetailMarkers.allTextContents();
    if (
      await heroDetailMarkers.count() !== 2 ||
      heroDetailMarkerText.some((marker) => marker !== ">")
    ) {
      throw new Error("showcase detail lines lack literal prompt markers");
    }
    if (
      await showcase.locator(
        "summary .detail-marker, details > .detail-marker",
      ).count() !== 0
    ) {
      throw new Error("showcase prompt marker resembles a disclosure control");
    }
    const performanceEvidence = showcase.locator(".performance-evidence");
    if (
      await performanceEvidence.count() !== 1 ||
      await performanceEvidence.locator(":scope > summary").count() !== 0
    ) {
      throw new Error(
        "showcase performance evidence is not always visible",
      );
    }
    if (
      await performanceEvidence.locator(".performance-results > p").count() !==
        3
    ) {
      throw new Error(
        "showcase does not separate representative, stress, and bundle results",
      );
    }
    if (
      await showcase.locator(".hero-facts, .measurement-method").count() !== 0
    ) {
      throw new Error("showcase retains duplicated facts or methodology");
    }
    const performanceContext = (await performanceEvidence.textContent())
      ?.replaceAll(/\s+/g, " ");
    for (const expected of [
      "Representative:",
      "200 group rows",
      "23.1 kB",
      "10.2 ms median",
      "not measured this browser",
      "Stress:",
      "10,000 rows",
      "4,900 findings",
      "177.1 ms median",
      "interpreter JS bundle",
    ]) {
      if (!performanceContext?.includes(expected)) {
        throw new Error(
          `showcase performance result lacks context '${expected}': ${
            performanceContext
          }`,
        );
      }
    }
    for (const removed of [
      "stress target passed",
      "conformance cases passed",
      "tested targets",
      "fresh Chrome runs",
      "observed range",
      "model preparation",
    ]) {
      if (performanceContext?.includes(removed)) {
        throw new Error(
          `showcase retains removed performance prose '${removed}': ${performanceContext}`,
        );
      }
    }
    const visibleShowcaseText = await showcase.locator("body").innerText();
    if (/(?:data|placement) document/i.test(visibleShowcaseText)) {
      throw new Error(
        "showcase qualifies the Kernel domain term 'Document'",
      );
    }
    const exampleDownloads = await showcase.locator(".example-downloads")
      .textContent();
    if (
      !exampleDownloads?.includes("No download is needed") ||
      !exampleDownloads.includes("inspect or reuse")
    ) {
      throw new Error(
        `showcase does not explain its optional downloads: ${exampleDownloads}`,
      );
    }
    const controlHierarchy = await showcase.evaluate(() => ({
      legacyControlBar: document.querySelector(".controls") !== null,
      scenario: document.querySelector("#scenario")
        ?.closest(".example-picker") !== null,
      workspaceMode: document.querySelector("#workspace-mode")
        ?.closest("details.workspace-controls") !== null,
      entryModel: document.querySelector("#entry-model-select")
        ?.closest("#entry-model-control") !== null,
      validationScope: document.querySelector("#validation-scope")
        ?.closest(".actions") !== null,
    }));
    if (
      controlHierarchy.legacyControlBar ||
      !controlHierarchy.scenario ||
      !controlHierarchy.workspaceMode ||
      !controlHierarchy.entryModel ||
      !controlHierarchy.validationScope
    ) {
      throw new Error(
        `showcase controls have no task hierarchy: ${
          JSON.stringify(controlHierarchy)
        }`,
      );
    }
    if (!(await showcase.locator("#entry-model-control").isHidden())) {
      throw new Error("entry model control is visible before custom files");
    }
    const manual = showcase.locator("details.manual");
    if (await manual.count() !== 1) {
      throw new Error("showcase does not expose a single instruction section");
    }
    if (await manual.getAttribute("open") !== null) {
      throw new Error("showcase instructions are expanded by default");
    }
    const manualText = await manual.textContent();
    for (const expected of [
      "How to use this page",
      "built-in example",
      "workspace folder",
      "Finite file map",
      "On-demand provider",
      "Full document",
      "Relevant scenario group",
      "same prepared interpreter and Document",
      "multiple runs report p95",
      "Nothing is uploaded",
    ]) {
      if (!manualText?.includes(expected)) {
        throw new Error(
          `showcase manual is missing ${JSON.stringify(expected)}: ${manualText}`,
        );
      }
    }
    if ((await showcase.getByText("What this proves").count()) !== 0) {
      throw new Error("showcase retains the marketing-only proof section");
    }
    if (
      (await showcase.getByText("01 · Input").count()) !== 0 ||
      (await showcase.getByText("02 · Output").count()) !== 0
    ) {
      throw new Error("showcase retains decorative workflow numbering");
    }
    const uploadButtons = showcase.locator(".file-button");
    if (await uploadButtons.count() !== 3) {
      throw new Error("showcase does not expose three upload buttons");
    }
    for (let index = 0; index < 3; index += 1) {
      const uploadButton = uploadButtons.nth(index);
      if (await uploadButton.locator(".file-button-label").count() !== 1) {
        throw new Error(`upload control ${index + 1} has no button surface`);
      }
      const buttonStyle = await uploadButton.locator(".file-button-label")
        .evaluate((element) => {
          const style = getComputedStyle(element);
          return {
            borderStyle: style.borderStyle,
            paddingBlockStart: Number.parseFloat(style.paddingBlockStart),
            paddingInlineStart: Number.parseFloat(style.paddingInlineStart),
          };
        });
      if (
        buttonStyle.borderStyle === "none" ||
        buttonStyle.paddingBlockStart < 8 ||
        buttonStyle.paddingInlineStart < 12
      ) {
        throw new Error(
          `upload control ${index + 1} is not visibly button-shaped`,
        );
      }
      const nativeInput = uploadButton.locator("input[type=file]");
      const nativeBounds = await nativeInput.boundingBox();
      if (
        nativeBounds !== null &&
        (nativeBounds.width > 1 || nativeBounds.height > 1)
      ) {
        throw new Error(
          `upload control ${index + 1} exposes native filename chrome`,
        );
      }
    }
    const assertOutput = async (
      operation: string,
      expected: readonly string[],
    ): Promise<void> => {
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
    const operationRuns = showcase.locator("#operation-runs");
    if (
      await operationRuns.count() !== 1 ||
      await operationRuns.inputValue() !== "1" ||
      await operationRuns.getAttribute("min") !== "1" ||
      await operationRuns.getAttribute("max") !== "100"
    ) {
      throw new Error(
        "showcase does not expose a bounded one-run operation control",
      );
    }
    const assertTiming = async (
      samples: number,
      metric: "elapsed" | "p95",
      operation: "validation" | "computation",
    ): Promise<void> => {
      const timing = showcase.locator(
        `#operation-timing[data-samples='${samples}'][data-metric='${metric}'][data-operation='${operation}']`,
      );
      await withDeadline(`${operation} timing`, timing.waitFor());
      const milliseconds = Number(await timing.getAttribute("data-millis"));
      if (!Number.isFinite(milliseconds) || milliseconds < 0) {
        throw new Error(
          `${operation} timing is invalid: ${milliseconds}`,
        );
      }
      const text = await timing.textContent();
      const expectedRuns = samples === 1 ? "one run" : `${samples} runs`;
      if (
        !text?.includes(expectedRuns) ||
        !text.includes("prepared interpreter") ||
        !text.includes("Document")
      ) {
        throw new Error(
          `${operation} timing lacks reusable-operation context: ${text}`,
        );
      }
    };

    await showcase.selectOption("#scenario", "clean");
    await showcase.click("#validate");
    await assertOutput("validate-full", ['"findings": []']);
    await assertTiming(1, "elapsed", "validation");

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
    ).filter({hasText: "Document loaded"}).waitFor();
    await showcase.click("#validate");
    await assertOutput("validate-full", ["AREA_NEEDS_NOTE"]);
    await assertTiming(1, "elapsed", "validation");

    await showcase.selectOption("#scenario", "firing");
    await showcase.selectOption("#validation-scope", "relevant");
    await operationRuns.fill("5");
    await showcase.click("#validate");
    await assertOutput("validate-relevant", ["AREA_NEEDS_NOTE"]);
    await assertTiming(5, "p95", "validation");
    await showcase.selectOption("#validation-scope", "full");

    await showcase.selectOption("#scenario", "formal-invalid");
    await showcase.click("#validate");
    await assertOutput("validate-full", ["zahlHatUngueltigeZeichen"]);

    await showcase.selectOption("#scenario", "computation");
    await operationRuns.fill("3");
    await showcase.click("#compute");
    await assertOutput("compute", ["/Permit/ReviewedArea", '"value": "125"']);
    await assertTiming(3, "p95", "computation");
    await operationRuns.fill("1");

    await showcase.click(".workspace-controls summary");
    await showcase.selectOption("#workspace-mode", "file-map");
    await showcase.selectOption("#scenario", "include-typedef");
    await showcase.locator("#workspace-folder-upload").setInputFiles(
      resolve("dist/workspace"),
    );
    await showcase.locator(
      "#status[data-state=passed]",
    ).filter({hasText: "Workspace files loaded"}).waitFor();
    if (!(await showcase.locator("#entry-model-control").isVisible())) {
      throw new Error("entry model control stays hidden after custom files");
    }
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
      `${url}?mode=performance&scenario=stress`,
    );
    const performanceStatus = performancePage.locator(
      "#status[data-state=passed][data-performance=passed][data-performance-scenario=stress][data-document-rows='10000'][data-expected-findings='4900']",
    );
    if (await performanceStatus.count() !== 1) {
      throw new Error(
        `stress browser performance scenario failed: ${
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
