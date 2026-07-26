import {mkdir, writeFile} from "node:fs/promises";
import {arch, cpus, platform, release} from "node:os";
import {dirname} from "node:path";
import process from "node:process";

import {
  launchChrome,
  openCheckedPage,
  withDeadline,
  withStaticServer,
} from "./harness.mjs";
import {
  BROWSER_FIRST_ANSWER_POLICY,
  enforceBrowserFirstAnswer,
} from "./performance-policy.mjs";

const samples = [];
let chromeVersion;
await withStaticServer(async (url) => {
  for (
    let index = 0;
    index < BROWSER_FIRST_ANSWER_POLICY.sampleCount;
    index += 1
  ) {
    const browser = await launchChrome();
    try {
      chromeVersion ??= browser.version();
      const page = await openCheckedPage(browser, `${url}?mode=performance`);
      const status = page.locator(
        "#status[data-state=passed][data-performance=passed]",
      );
      if (await status.count() !== 1) {
        throw new Error(
          `browser performance sample ${index + 1} failed: ${
            await page.locator("#status").textContent()
          }`,
        );
      }
      const value = Number(
        await status.getAttribute("data-first-answer-millis"),
      );
      if (!Number.isFinite(value) || value <= 0) {
        throw new Error(
          `browser performance sample ${index + 1} is invalid: ${value}`,
        );
      }
      samples.push(value);
    } finally {
      await withDeadline("Chrome shutdown", browser.close());
    }
    if ((index + 1) % 10 === 0) {
      console.log(
        `browser first-answer samples: ${index + 1}/${
          BROWSER_FIRST_ANSWER_POLICY.sampleCount
        }`,
      );
    }
  }
});

const cpu = cpus()[0]?.model ?? "unknown";
const estimator = enforceBrowserFirstAnswer(samples);
const report = {
  format: "a12-browser-first-answer-v1",
  informativeUntilOwnerRatification: false,
  commit: process.env.INTERPRETER_COMMIT ?? "unknown",
  scenario: {
    id: "browser-b1-10000-rows-first-answer",
    version: 1,
    workload: "b1-firing:flat-lines=10000:v1",
    timedRegion: "createInterpreter-through-complete-validateFull",
    preparationTimed: false,
    documentMaterializationTimed: false,
    rows: 10_000,
    expectedFindings: 4_900,
    expectedCode: "HQ",
  },
  runtime: {
    browser: chromeVersion,
    os: `${platform()} ${release()}`,
    arch: arch(),
    cpu,
  },
  estimator: {
    ...estimator,
    independence: "one fresh Chrome process per sample",
  },
  releaseBar: {
    metric: "nearest-rank-p95",
    maximumMillis: BROWSER_FIRST_ANSWER_POLICY.maximumP95Millis,
    ratifiedOn: BROWSER_FIRST_ANSWER_POLICY.ratifiedOn,
    passed: true,
  },
  samplesMillis: samples,
};

const output = `${JSON.stringify(report, null, 2)}\n`;
const reportPath = process.env.BROWSER_PERFORMANCE_REPORT;
if (reportPath) {
  await mkdir(dirname(reportPath), {recursive: true});
  await writeFile(reportPath, output, "utf8");
}
console.log(output);
