import {
  ModelWorkspace,
  type DocumentInputV1,
  type FieldPlacementInput,
  type GroupPlacementInput,
} from "@mbackschat/a12-interpreter";

export enum BrowserPerformanceScenario {
  REPRESENTATIVE = "representative",
  STRESS = "stress",
}

interface BrowserPerformanceCase {
  readonly rows: number;
  readonly expectedFindings: number;
  readonly document: DocumentInputV1;
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

const stressFields = (rows: number): readonly FieldPlacementInput[] => {
  const placements: FieldPlacementInput[] = [];
  for (let row = 1; row <= rows; row += 1) {
    placements.push(
      {
        path: "/Perf/Lines/LineKey",
        coordinates: [1, row, 1],
        raw: `K1_${row}_1`,
      },
      {
        path: "/Perf/Lines/Qty",
        coordinates: [1, row, 1],
        raw: (row % 100).toString(),
      },
      {
        path: "/Perf/Lines/Amount",
        coordinates: [1, row, 1],
        raw: "1",
      },
    );
  }
  return placements;
};

const representativeDocument = (): DocumentInputV1 => {
  const groups: GroupPlacementInput[] = [];
  const fields: FieldPlacementInput[] = [];
  for (let row = 1; row <= 200; row += 1) {
    groups.push({
      path: "/Perf/Lines",
      coordinates: [1, row],
    });
    fields.push({
      path: "/Perf/Lines/LineKey",
      coordinates: [1, row, 1],
      raw: `K1_${row}_1`,
    });
  }
  return {version: 1, groups, fields};
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
        document: {
          version: 1,
          groups: [],
          fields: stressFields(10_000),
        },
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
