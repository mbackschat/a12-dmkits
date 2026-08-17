import {
  ModelWorkspace,
  type A12Document,
  type A12DocumentGroup,
} from "@mbackschat/a12-interpreter";

export enum BrowserPerformanceScenario {
  REPRESENTATIVE = "representative",
  STRESS = "stress",
}

interface BrowserPerformanceCase {
  readonly rows: number;
  readonly expectedFindings: number;
  readonly document: A12Document;
}

export interface BrowserPerformanceMeasurement {
  readonly scenario: BrowserPerformanceScenario;
  readonly rows: number;
  readonly documentBytes: number;
  /** Warm validation only: the interpreter is built OUTSIDE the clock, as the JVM lane measures it. */
  readonly warmValidationMillis: number;
  readonly expectedFindings: number;
  readonly elapsedMillis: number;
}

const fetchModel = async (): Promise<string> => {
  const response = await fetch(
    `${import.meta.env.BASE_URL}performance/b1.dm.json`,
  );
  if (!response.ok) {
    throw new Error(
      `browser performance model request failed with HTTP ${response.status}`,
    );
  }
  return response.text();
};

/** One `/Perf/Lines` row per repetition — the array index IS the repetition in A12 Document JSON. */
const stressDocument = (rows: number): A12Document => {
  const lines: A12DocumentGroup[] = [];
  for (let row = 1; row <= rows; row += 1) {
    lines.push({
      LineKey: `K1_${row}_1`,
      Qty: row % 100,
      Amount: 1,
    });
  }
  return {Perf: {Lines: lines}};
};

const representativeDocument = (): A12Document => {
  const lines: A12DocumentGroup[] = [];
  for (let row = 1; row <= 200; row += 1) {
    lines.push({LineKey: `K1_${row}_1`});
  }
  return {Perf: {Lines: lines}};
};

/** Repeats behind the warm median. Enough to leave the first-run effects, cheap enough per process. */
const WARM_VALIDATION_RUNS = 20;

const performanceCase = (
  scenario: BrowserPerformanceScenario,
): BrowserPerformanceCase => {
  switch (scenario) {
    case BrowserPerformanceScenario.REPRESENTATIVE:
      return {
        rows: 200,
        expectedFindings: 0,
        document: representativeDocument(),
      };
    case BrowserPerformanceScenario.STRESS:
      return {
        rows: 10_000,
        expectedFindings: 4_900,
        document: stressDocument(10_000),
      };
  }
};

/**
 * Measures interpreter construction through complete validation against an already prepared model and decoded
 * Document. Preparation and Document materialization stay outside the clock for both fixed scenarios.
 */
export const measureBrowserFirstAnswer = async (
  scenario: BrowserPerformanceScenario,
): Promise<BrowserPerformanceMeasurement> => {
  const fixedCase = performanceCase(scenario);
  const modelJson = await fetchModel();
  const prepared = ModelWorkspace.fromSources([{json: modelJson}])
    .prepare("perf");
  const snapshot = prepared.documents.readDocument(fixedCase.document);

  const started = performance.now();
  const report = prepared.createInterpreter().validateFull(snapshot);
  const elapsedMillis = performance.now() - started;
  if (
    !report.fullySupported ||
    report.unsupported.length !== 0 ||
    report.findings.length !== fixedCase.expectedFindings ||
    report.findings.some(({code}) => code !== "HQ")
  ) {
    throw new Error(
      `${scenario} browser semantics differ: supported=${report.fullySupported} ` +
      `unsupported=${report.unsupported.length} findings=${report.findings.length}`,
    );
  }
  // WARM, validation only — the counterpart of the JVM lane's `timeInterpreter`, which builds the interpreter
  // outside the clock and times repeated runs. Measured in the same fresh process as the cold figure above so
  // both describe one browser, and asserted on every run so a degraded engine cannot report a fast warm time.
  const warmInterpreter = prepared.createInterpreter();
  const warmSamples: number[] = [];
  for (let run = 0; run < WARM_VALIDATION_RUNS; run += 1) {
    const warmStarted = performance.now();
    const warmReport = warmInterpreter.validateFull(snapshot);
    warmSamples.push(performance.now() - warmStarted);
    if (warmReport.findings.length !== fixedCase.expectedFindings) {
      throw new Error(
        `${scenario} warm validation differs: findings=${warmReport.findings.length}`,
      );
    }
  }
  const ordered = [...warmSamples].sort((left, right) => left - right);
  const warmValidationMillis = ordered[Math.floor(ordered.length / 2)]!;

  return {
    scenario,
    rows: fixedCase.rows,
    documentBytes: new TextEncoder().encode(
      JSON.stringify(fixedCase.document),
    ).byteLength,
    expectedFindings: fixedCase.expectedFindings,
    elapsedMillis,
    warmValidationMillis,
  };
};
