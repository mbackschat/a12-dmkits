import {
  ModelWorkspace,
  type FieldPlacementInput,
} from "@mbackschat/a12-interpreter";

const ROWS = 10_000;
const EXPECTED_FINDINGS = 4_900;

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

const fields = (): readonly FieldPlacementInput[] => {
  const placements: FieldPlacementInput[] = [];
  for (let row = 1; row <= ROWS; row += 1) {
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

/**
 * The browser peer of the existing Node B1 first-answer scenario. Model preparation and document materialization
 * stay outside the clock; the sample begins immediately before interpreter construction and ends only after the
 * complete, semantically checked report is materialized.
 */
export const measureBrowserFirstAnswer = async (): Promise<number> => {
  const modelJson = await fetchModel();
  const prepared = ModelWorkspace.fromSources([{json: modelJson}])
    .prepare("perf");
  const snapshot = prepared.documents.readDocument({
    version: 1,
    groups: [],
    fields: fields(),
  });

  const started = performance.now();
  const report = prepared.createInterpreter().validateFull(snapshot);
  const elapsedMillis = performance.now() - started;
  if (
    !report.fullySupported ||
    report.unsupported.length !== 0 ||
    report.findings.length !== EXPECTED_FINDINGS ||
    report.findings.some(({code}) => code !== "HQ")
  ) {
    throw new Error(
      `browser first-answer semantics differ: supported=${report.fullySupported} ` +
      `unsupported=${report.unsupported.length} findings=${report.findings.length}`,
    );
  }
  return elapsedMillis;
};
