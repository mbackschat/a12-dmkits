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

const SAMPLE_COUNT = 50;
const PERCENTILE = 0.95;

const median = (values) => {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
};

const nearestRank = (values, percentile) => {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.ceil(percentile * sorted.length) - 1];
};

const samples = [];
let chromeVersion;
await withStaticServer(async (url) => {
  for (let index = 0; index < SAMPLE_COUNT; index += 1) {
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
      console.log(`browser first-answer samples: ${index + 1}/${SAMPLE_COUNT}`);
    }
  }
});

const cpu = cpus()[0]?.model ?? "unknown";
const report = {
  format: "a12-browser-first-answer-v1",
  informativeUntilOwnerRatification: true,
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
    sampleCount: samples.length,
    independence: "one fresh Chrome process per sample",
    discardedSamples: 0,
    percentile: PERCENTILE,
    medianMillis: median(samples),
    nearestRankP95Millis: nearestRank(samples, PERCENTILE),
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
