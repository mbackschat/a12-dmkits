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
  return {
    scenario,
    rows: fixedCase.rows,
    documentBytes: new TextEncoder().encode(
      JSON.stringify(fixedCase.document),
    ).byteLength,
    expectedFindings: fixedCase.expectedFindings,
    elapsedMillis,
  };
};
