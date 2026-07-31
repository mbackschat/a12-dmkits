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
    const releaseIdentity = await showcase.evaluate(async () => {
      const response = await fetch("release.json");
      if (!response.ok) {
        throw new Error(`release identity failed with HTTP ${response.status}`);
      }
      return await response.json() as {
        version: string;
        a12Kernel: string;
        a12Distribution: string;
      };
    });
    if (await version.textContent() !== releaseIdentity.version) {
      throw new Error(
        `showcase package version mismatch: ${await version.textContent()}`,
      );
    }
    const semanticsTarget = await showcase
      .locator("#semantics-target")
      .textContent();
    const expectedSemanticsTarget =
      `A12 Kernel ${releaseIdentity.a12Kernel}`
      + ` · A12 Tools ${releaseIdentity.a12Distribution}`;
    if (semanticsTarget !== expectedSemanticsTarget) {
      throw new Error(
        `showcase semantics target mismatch: ${semanticsTarget}`,
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
    const privacyNotice = showcase.locator(
      "footer a[href='../privacy.html']",
    );
    if (
      await privacyNotice.count() !== 1 ||
      await privacyNotice.textContent() !== "Privacy notice"
    ) {
      throw new Error(
        "showcase does not link its site-specific privacy notice",
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
      validationRadios: Array.from(
        document.querySelectorAll<HTMLInputElement>(
          'input[name="validation-scope"]',
        ),
      ).map(({id, value, checked}) => ({id, value, checked})),
      validationBeforeRuns: (() => {
        const validation = document.querySelector("#validation-scope");
        const runs = document.querySelector(".operation-runs-control");
        return validation !== null &&
          runs !== null &&
          (validation.compareDocumentPosition(runs) &
            Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
      })(),
      workspaceToolsLabel: document.querySelector(
        ".model-workspace-actions > span",
      )?.textContent?.trim(),
      workspaceToolButtons: Array.from(
        document.querySelectorAll<HTMLButtonElement>(
          ".model-workspace-actions button",
        ),
      ).map(({id}) => id),
      documentActionButtons: Array.from(
        document.querySelectorAll<HTMLButtonElement>(".actions button"),
      ).map(({id}) => id),
      workspaceToolsBetweenConfigurationAndDocumentActions: (() => {
        const configuration = document.querySelector(".advanced-controls");
        const workspaceTools = document.querySelector(
          ".model-workspace-actions",
        );
        const documentActions = document.querySelector(".actions");
        return configuration !== null &&
          workspaceTools !== null &&
          documentActions !== null &&
          (configuration.compareDocumentPosition(workspaceTools) &
            Node.DOCUMENT_POSITION_FOLLOWING) !== 0 &&
          (workspaceTools.compareDocumentPosition(documentActions) &
            Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
      })(),
    }));
    if (
      controlHierarchy.legacyControlBar ||
      !controlHierarchy.scenario ||
      !controlHierarchy.workspaceMode ||
      !controlHierarchy.entryModel ||
      !controlHierarchy.validationScope ||
      JSON.stringify(controlHierarchy.validationRadios) !== JSON.stringify([
        {id: "validation-scope-full", value: "full", checked: true},
        {id: "validation-scope-partial", value: "partial", checked: false},
      ]) ||
      !controlHierarchy.validationBeforeRuns ||
      controlHierarchy.workspaceToolsLabel !== "Model workspace:" ||
      JSON.stringify(controlHierarchy.workspaceToolButtons) !==
        JSON.stringify(["prepare", "check-support"]) ||
      JSON.stringify(controlHierarchy.documentActionButtons) !==
        JSON.stringify([
          "validate",
          "compute",
          "apply-computations",
          "compute-apply-validate",
        ]) ||
      !controlHierarchy.workspaceToolsBetweenConfigurationAndDocumentActions
    ) {
      throw new Error(
        `showcase controls have no task hierarchy: ${
          JSON.stringify(controlHierarchy)
        }`,
      );
    }
    const buttonTooltips = await showcase.locator("button").evaluateAll(
      (buttons) => buttons.map((button) => ({
        id: button.id,
        title: button.getAttribute("title")?.trim() ?? "",
      })),
    );
    const buttonsWithoutTooltips = buttonTooltips.filter(
      ({id, title}) => id.length === 0 || title.length === 0,
    );
    if (buttonsWithoutTooltips.length > 0) {
      throw new Error(
        `showcase buttons lack explanatory tooltips: ${
          JSON.stringify(buttonsWithoutTooltips)
        }`,
      );
    }
    if (!(await showcase.locator("#entry-model-control").isHidden())) {
      throw new Error("entry model control is visible before custom files");
    }
    const modelInspector = showcase.locator(
      ".result-panel > details#model-inspector",
    );
    if (await modelInspector.count() !== 1) {
      throw new Error(
        "the prepared DocumentModel inspector is not inside the result panel",
      );
    }
    if (await modelInspector.getAttribute("open") !== null) {
      throw new Error("the DocumentModel inspector is expanded by default");
    }
    const modelInspectorOrder = await showcase.evaluate(() => {
      const inspector = document.querySelector("#model-inspector");
      const resultTitle = document.querySelector("#result-title");
      return inspector !== null && resultTitle !== null &&
        Boolean(inspector.compareDocumentPosition(resultTitle) &
          Node.DOCUMENT_POSITION_FOLLOWING);
    });
    if (!modelInspectorOrder) {
      throw new Error("the DocumentModel inspector is not above Result");
    }
    await showcase.setViewportSize({width: 1_800, height: 1_000});
    const wideWorkbench = await showcase.evaluate(() => {
      const rectangle = (selector: string): DOMRect => {
        const element = document.querySelector(selector);
        if (!(element instanceof HTMLElement)) {
          throw new Error(`wide-layout element '${selector}' is missing`);
        }
        return element.getBoundingClientRect();
      };
      const workbench = rectangle(".workbench");
      const editor = rectangle(".editor-panel");
      const result = rectangle(".result-panel");
      const input = rectangle("#document-input");
      return {
        workbenchWidth: workbench.width,
        editorWidth: editor.width,
        resultWidth: result.width,
        inputWidth: input.width,
      };
    });
    if (
      wideWorkbench.workbenchWidth < 1_700 ||
      wideWorkbench.editorWidth < 800 ||
      wideWorkbench.resultWidth < 800 ||
      wideWorkbench.inputWidth < wideWorkbench.editorWidth * 0.8
    ) {
      throw new Error(
        `the editor workbench remains width-capped in a wide browser: ${
          JSON.stringify(wideWorkbench)
        }`,
      );
    }
    const builtInModelJson = await modelInspector.locator("#model-json")
      .textContent();
    for (const expected of [
      '"id": "permit-basic"',
      '"ApplicantName"',
      '"id": "/Permit/SiteSections"',
      '"mode": "absoluteOrRelativeToNextRepAncestor"',
      '"CalculateEstimatedFee"',
      '"externalDescription"',
    ]) {
      if (!builtInModelJson?.includes(expected)) {
        throw new Error(
          `the built-in DocumentModel inspector lacks ${
            JSON.stringify(expected)
          }: ${builtInModelJson}`,
        );
      }
    }
    const expectedPermitMembers = [
      "ApplicationNo",
      "ApplicantName",
      "PermitType",
      "SubmittedOn",
      "RequestedArea",
      "UnitFee",
      "ReviewedArea",
      "EstimatedFee",
      "Note",
      "Rate",
      "PatternProbe",
      "GraphemeProbe",
      "ReportedAt",
      "SettledAt",
      "SiteSections",
    ].sort();
    type PermitDocument = {Permit?: Record<string, unknown>};
    const assertPopulatedSiteSections = (
      document: PermitDocument,
      context: string,
      expectedRows: number,
    ): void => {
      const sections = document.Permit?.SiteSections;
      if (
        !Array.isArray(sections) ||
        sections.length !== expectedRows ||
        sections.some((row) =>
          typeof row !== "object" ||
          row === null ||
          !Object.hasOwn(row, "SectionName") ||
          !Object.hasOwn(row, "Area") ||
          (row as Record<string, unknown>).SectionName === null ||
          (row as Record<string, unknown>).Area === null
        )
      ) {
        throw new Error(
          `${context} lacks ${expectedRows} populated SiteSections rows: ${
            JSON.stringify(document)
          }`,
        );
      }
    };
    const builtInDocument = JSON.parse(
      await showcase.locator("#document-input").inputValue(),
    ) as PermitDocument;
    if (
      builtInDocument.Permit === undefined ||
      Object.keys(builtInDocument.Permit).sort().join(",") !==
        expectedPermitMembers.join(",")
    ) {
      throw new Error(
        `the built-in Document omits model fields: ${
          JSON.stringify(builtInDocument)
        }`,
      );
    }
    assertPopulatedSiteSections(builtInDocument, "built-in Document", 2);
    const bundledPermitDocuments = await showcase.evaluate(async () => {
      const paths = [
        "examples/documents/permit-valid.json",
        "examples/documents/permit-rule-firing.json",
        "examples/documents/permit-formal-invalid.json",
        "examples/documents/permit-computation.json",
        "examples/documents/permit-computed-error.json",
      ];
      return await Promise.all(paths.map(async (path) => {
        const response = await fetch(path);
        if (!response.ok) {
          throw new Error(`${path} failed with HTTP ${response.status}`);
        }
        return await response.json();
      }));
    }) as PermitDocument[];
    bundledPermitDocuments.forEach((document, index) =>
      assertPopulatedSiteSections(
        document,
        `bundled permit Document ${index + 1}`,
        2,
      )
    );
    const starterButton = showcase.locator("#seed-document");
    if (
      await starterButton.count() !== 1 ||
      await starterButton.textContent() !== "Use model-shaped starter"
    ) {
      throw new Error("showcase does not expose the model-shaped Document seed");
    }
    const applyButton = showcase.locator("#apply-computations");
    const formFlowButton = showcase.locator("#compute-apply-validate");
    if (
      await applyButton.count() !== 1 ||
      await applyButton.textContent() !== "Apply results" ||
      !(await applyButton.isDisabled()) ||
      await formFlowButton.count() !== 1 ||
      await formFlowButton.textContent() !== "Compute → Apply → Validate"
    ) {
      throw new Error(
        "showcase does not expose the retained-result and form-engine computation workflows",
      );
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
      "Full validates",
      "Partial validates",
      "same prepared interpreter and Document",
      "multiple runs report p95",
      "model-shaped starter",
      "Validate checks the current Document as-is",
      "implicit consistency rule",
      "Compute → Apply → Validate",
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
      await withDeadline(
        `${operation} expected output`,
        showcase.waitForFunction(
          ({operation, expected}) => {
            const output = document.querySelector(
              `#result[data-operation='${operation}']`,
            );
            return output !== null &&
              expected.every((fragment) =>
                output.textContent?.includes(fragment) === true);
          },
          {operation, expected},
        ),
      );
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
      await operationRuns.getAttribute("max") !== "100000"
    ) {
      throw new Error(
        "showcase does not expose a bounded one-run operation control",
      );
    }
    const assertTiming = async (
      samples: number,
      metric: "elapsed" | "p95",
      operation: "validation" | "computation" | "form-flow",
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

    await showcase.locator("#document-input").fill("{}");
    await showcase.click("#seed-document");
    const explicitSeed = JSON.parse(
      await showcase.locator("#document-input").inputValue(),
    ) as PermitDocument;
    if (
      explicitSeed.Permit === undefined ||
      Object.keys(explicitSeed.Permit).sort().join(",") !==
        expectedPermitMembers.join(",")
    ) {
      throw new Error(
        `explicit model-shaped seed omitted fields: ${
          JSON.stringify(explicitSeed)
        }`,
      );
    }
    assertPopulatedSiteSections(explicitSeed, "model-shaped starter", 1);
    if (
      explicitSeed.Permit.ReviewedArea !== null ||
      explicitSeed.Permit.EstimatedFee !== null
    ) {
      throw new Error(
        `model-shaped seed filled computation-owned targets: ${
          JSON.stringify(explicitSeed)
        }`,
      );
    }
    await showcase.click("#validate");
    await assertOutput("validate-full", ['"findings": []']);

    await showcase.selectOption("#scenario", "clean");
    await showcase.click("#check-support");
    await assertOutput("support-report", [
      '"unsupported": []',
      '"fullySupported": true',
    ]);
    await showcase.click("#validate");
    await assertOutput("validate-full", ['"findings": []']);
    await assertTiming(1, "elapsed", "validation");

    await showcase.locator("#document-upload").setInputFiles({
      name: "permit-input.json",
      mimeType: "application/json",
      buffer: Buffer.from(JSON.stringify({
        Permit: {
          ApplicationNo: "P-UPLOAD",
          ApplicantName: "Uploaded Applicant",
          PermitType: "PUBLIC",
          SubmittedOn: "2026-07-19",
          RequestedArea: 120,
          UnitFee: 1.5,
        },
      })),
    });
    await showcase.locator(
      "#status[data-state=passed]",
    ).filter({hasText: "Document loaded"}).waitFor();
    await showcase.click("#validate");
    await assertOutput("validate-full", ["AREA_NEEDS_NOTE"]);
    await assertTiming(1, "elapsed", "validation");

    await showcase.selectOption("#scenario", "firing");
    await showcase.check("#validation-scope-partial");
    await operationRuns.fill("5");
    await showcase.click("#validate");
    await assertOutput("validate-partial", ["AREA_NEEDS_NOTE"]);
    await assertTiming(5, "p95", "validation");
    await showcase.check("#validation-scope-full");

    await showcase.selectOption("#scenario", "formal-invalid");
    await showcase.click("#validate");
    await assertOutput("validate-full", ["zahlHatUngueltigeZeichen"]);
    await showcase.click("#compute");
    await assertOutput("compute", [
      '"formalErrorsInOperands"',
      '"zahlHatUngueltigeZeichen"',
      '"errorPath": "/Permit[1]/RequestedArea"',
      '"noErrorOccurred": false',
    ]);

    await showcase.selectOption("#scenario", "computed-error");
    await showcase.click("#compute");
    await assertOutput("compute", [
      '"outcome": "errored"',
      '"diagnostic"',
      '"stelligkeitZuLang"',
      '"errorPath": "/Permit[1]/EstimatedFee"',
      '"formalErrorsInOperands": []',
      '"noErrorOccurred": false',
    ]);

    await showcase.selectOption("#scenario", "computation");
    await operationRuns.fill("3");
    await showcase.click("#compute");
    await assertOutput("compute", [
      "/Permit/ReviewedArea",
      "/Permit/EstimatedFee",
      '"value": "125"',
    ]);
    await assertTiming(3, "p95", "computation");
    if (await applyButton.isDisabled()) {
      throw new Error("a retained computation result cannot be applied");
    }
    await showcase.click("#apply-computations");
    await assertOutput("apply-computations", [
      '"ReviewedArea": 125',
      '"EstimatedFee": 312.5',
      '"appliedActions": 2',
    ]);
    const appliedDocument = JSON.parse(
      await showcase.locator("#document-input").inputValue(),
    ) as {Permit?: Record<string, unknown>};
    if (
      appliedDocument.Permit?.ReviewedArea !== 125 ||
      appliedDocument.Permit?.EstimatedFee !== 312.5 ||
      !(await applyButton.isDisabled())
    ) {
      throw new Error(
        `applying computation results did not update the editable Document exactly once: ${
          JSON.stringify(appliedDocument)
        }`,
      );
    }
    await showcase.click("#validate");
    await assertOutput("validate-full", ['"findings": []']);

    await showcase.selectOption("#scenario", "computation");
    await operationRuns.fill("1");
    await showcase.click("#compute-apply-validate");
    await assertOutput("compute-apply-validate-full", [
      '"ReviewedArea": 125',
      '"EstimatedFee": 312.5',
      '"findings": []',
      '"appliedActions": 2',
    ]);
    await assertTiming(1, "elapsed", "form-flow");
    const formFlowDocument = JSON.parse(
      await showcase.locator("#document-input").inputValue(),
    ) as {Permit?: Record<string, unknown>};
    if (
      formFlowDocument.Permit?.ReviewedArea !== 125 ||
      formFlowDocument.Permit?.EstimatedFee !== 312.5
    ) {
      throw new Error(
        `form-engine workflow did not retain its applied Document: ${
          JSON.stringify(formFlowDocument)
        }`,
      );
    }
    await showcase.click(".workspace-controls summary");
    await showcase.selectOption("#workspace-mode", "file-map");
    await showcase.selectOption("#scenario", "clean");
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
    const uploadedModelJson = await modelInspector.locator("#model-json")
      .textContent();
    for (const expected of [
      '"id": "permit-single"',
      '"NegativeScore"',
      '"CopyScore"',
    ]) {
      if (!uploadedModelJson?.includes(expected)) {
        throw new Error(
          `the expanded uploaded DocumentModel lacks ${
            JSON.stringify(expected)
          }: ${uploadedModelJson}`,
        );
      }
    }
    const uploadedSeed = JSON.parse(
      await showcase.locator("#document-input").inputValue(),
    ) as {
      Permit?: {
        Review?: {
          Inspection?: Record<string, unknown>;
          [name: string]: unknown;
        };
        [name: string]: unknown;
      };
    };
    const seededReview = uploadedSeed.Permit?.Review;
    const seededInspection = seededReview?.Inspection;
    for (const expected of [
      "ApplicationCode",
      "InspectionDisposition",
      "Review",
    ]) {
      if (!Object.hasOwn(uploadedSeed.Permit ?? {}, expected)) {
        throw new Error(
          `uploaded model seed lacks Permit.${expected}: ${
            JSON.stringify(uploadedSeed)
          }`,
        );
      }
    }
    for (const expected of ["Name", "Inspection"]) {
      if (!Object.hasOwn(seededReview ?? {}, expected)) {
        throw new Error(
          `uploaded model seed lacks Permit.Review.${expected}: ${
            JSON.stringify(uploadedSeed)
          }`,
        );
      }
    }
    for (const expected of ["Score", "Summary", "Disposition", "Result"]) {
      if (!Object.hasOwn(seededInspection ?? {}, expected)) {
        throw new Error(
          `uploaded model seed lacks Permit.Review.Inspection.${expected}: ${
            JSON.stringify(uploadedSeed)
          }`,
        );
      }
    }
    await showcase.locator("#document-upload").setInputFiles({
      name: "permit-workspace-invalid.json",
      mimeType: "application/json",
      buffer: Buffer.from(JSON.stringify({
        Permit: {
          ApplicationCode: "ABCDEFGHIJKLM",
          Review: {Inspection: {Score: -1}},
        },
      })),
    });
    await showcase.locator(
      "#status[data-state=passed]",
    ).filter({hasText: "Document loaded"}).waitFor();
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
