import {mkdir, writeFile} from "node:fs/promises";
import {arch, cpus, platform, release} from "node:os";
import {dirname} from "node:path";
import process from "node:process";

import {
  closeChrome,
  launchChrome,
  openCheckedPage,
  withDeadline,
  withStaticServer,
} from "./harness.js";
import {
  BROWSER_FIRST_ANSWER_POLICY,
  enforceBrowserFirstAnswer,
  requiresExtendedBrowserSeries,
  summarizeBrowserSamples,
} from "./performance-policy.js";
import {browserPerformanceReportHeader} from "./performance-provenance.js";

enum PerformanceScenarioId {
  REPRESENTATIVE = "representative",
  STRESS = "stress",
}

interface PerformanceScenario {
  readonly id: PerformanceScenarioId;
  readonly workload: string;
  readonly rows: number;
  readonly expectedFindings: number;
  readonly expectedCode?: string;
  readonly releaseBar: boolean;
}

interface PerformanceMeasurement {
  readonly documentBytes: number;
  readonly samples: readonly number[];
  readonly warmSamples: readonly number[];
}

const scenarios: readonly PerformanceScenario[] = [
  {
    id: PerformanceScenarioId.REPRESENTATIVE,
    workload: "b1-valid:flat-lines=200:index-fields-only:v1",
    rows: 200,
    expectedFindings: 0,
    releaseBar: false,
  },
  {
    id: PerformanceScenarioId.STRESS,
    workload: "b1-firing:flat-lines=10000:v1",
    rows: 10_000,
    expectedFindings: 4_900,
    expectedCode: "HQ",
    releaseBar: true,
  },
];

const measurements = new Map<PerformanceScenarioId, PerformanceMeasurement>();
let chromeVersion: string | undefined;
await withStaticServer(async (url) => {
  for (const scenario of scenarios) {
    const samples: number[] = [];
    const warmSamples: number[] = [];
    let documentBytes: number | undefined;
    const collectUntil = async (targetCount: number): Promise<void> => {
      for (let index = samples.length; index < targetCount; index += 1) {
        const browser = await launchChrome();
        try {
          chromeVersion ??= browser.version();
          const page = await openCheckedPage(
            browser,
            `${url}?mode=performance&scenario=${scenario.id}`,
          );
          const status = page.locator(
            `#status[data-state=passed][data-performance=passed]` +
            `[data-performance-scenario='${scenario.id}']` +
            `[data-document-rows='${scenario.rows}']` +
            `[data-expected-findings='${scenario.expectedFindings}']`,
          );
          if (await status.count() !== 1) {
            throw new Error(
              `${scenario.id} browser performance sample ${
                index + 1
              } failed: ${await page.locator("#status").textContent()}`,
            );
          }
          const value = Number(
            await status.getAttribute("data-first-answer-millis"),
          );
          if (!Number.isFinite(value) || value <= 0) {
            throw new Error(
              `${scenario.id} browser performance sample ${
                index + 1
              } is invalid: ${value}`,
            );
          }
          const observedDocumentBytes = Number(
            await status.getAttribute("data-document-bytes"),
          );
          if (
            !Number.isSafeInteger(observedDocumentBytes) ||
            observedDocumentBytes <= 0 ||
            (documentBytes !== undefined &&
              observedDocumentBytes !== documentBytes)
          ) {
            throw new Error(
              `${scenario.id} Document byte size changed between samples: ` +
              `${documentBytes ?? "unset"} -> ${observedDocumentBytes}`,
            );
          }
          documentBytes = observedDocumentBytes;
          samples.push(value);
          const warm = Number(
            await status.getAttribute("data-warm-validation-millis"),
          );
          if (!Number.isFinite(warm) || warm <= 0) {
            throw new Error(
              `${scenario.id} warm validation sample ${index + 1} is invalid: ${warm}`,
            );
          }
          warmSamples.push(warm);
        } finally {
          await closeChrome(browser);
        }
      }
      console.log(
        `${scenario.id} browser first-answer samples: ` +
        `${samples.length}/${targetCount}`,
      );
    };
    await collectUntil(BROWSER_FIRST_ANSWER_POLICY.initialSampleCount);
    if (requiresExtendedBrowserSeries(summarizeBrowserSamples(samples))) {
      console.log(
        `${scenario.id} timing range is noisy; extending the series to ` +
        `${BROWSER_FIRST_ANSWER_POLICY.maximumSampleCount} samples`,
      );
      await collectUntil(BROWSER_FIRST_ANSWER_POLICY.maximumSampleCount);
    }
    if (documentBytes === undefined) {
      throw new Error(`${scenario.id} browser measurement has no Document size`);
    }
    measurements.set(scenario.id, {documentBytes, samples, warmSamples});
  }
});

const cpu = cpus()[0]?.model ?? "unknown";
const measuredScenarios = Object.fromEntries(scenarios.map((scenario) => {
  const measurement = measurements.get(scenario.id);
  if (measurement === undefined) {
    throw new Error(`missing ${scenario.id} browser measurement`);
  }
  const estimator = scenario.releaseBar
    ? enforceBrowserFirstAnswer(measurement.samples)
    : summarizeBrowserSamples(measurement.samples);
  return [
    scenario.id,
    {
      id: scenario.id === PerformanceScenarioId.REPRESENTATIVE
        ? "browser-b1-200-rows-first-answer"
        : "browser-b1-10000-rows-first-answer",
      version: 1,
      workload: scenario.workload,
      timedRegion: "createInterpreter-through-complete-validateFull",
      preparationTimed: false,
      documentMaterializationTimed: false,
      rows: scenario.rows,
      documentBytes: measurement.documentBytes,
      expectedFindings: scenario.expectedFindings,
      ...(scenario.expectedCode === undefined
        ? {}
        : {expectedCode: scenario.expectedCode}),
      estimator: {
        ...estimator,
        independence: "one fresh Chrome process per sample",
      },
      releaseBar: scenario.releaseBar
        ? {
          metric: "median",
          maximumMillis: BROWSER_FIRST_ANSWER_POLICY.maximumMedianMillis,
          ratifiedOn: BROWSER_FIRST_ANSWER_POLICY.ratifiedOn,
          passed: true,
        }
        : null,
      // Warm, validation only: interpreter built outside the clock, the JVM lane's timed region.
      warmValidation: {
        timedRegion: "validateFull-only-warm-prepared-interpreter",
        medianMillis: (() => {
          const o = [...measurement.warmSamples].sort((l, r) => l - r);
          return o[Math.floor(o.length / 2)]!;
        })(),
        sampleCount: measurement.warmSamples.length,
      },
      samplesMillis: measurement.samples,
    },
  ];
}));
const report = {
  ...browserPerformanceReportHeader(process.env),
  runtime: {
    browser: chromeVersion,
    os: `${platform()} ${release()}`,
    arch: arch(),
    cpu,
  },
  scenarios: measuredScenarios,
};

const output = `${JSON.stringify(report, null, 2)}\n`;
const reportPath = process.env.BROWSER_PERFORMANCE_REPORT;
if (reportPath) {
  await mkdir(dirname(reportPath), {recursive: true});
  await writeFile(reportPath, output, "utf8");
}
console.log(output);
