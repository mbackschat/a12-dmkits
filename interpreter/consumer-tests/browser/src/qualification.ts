import {
  type ComputationResult,
  ModelWorkspace,
  type DocumentInputV1,
  type PreparedModel,
  type ValidationFinding,
} from "@mbackschat/a12-interpreter";

const EXPECTED_CORPUS_CASES = 33;
const DEFERRED_CLOCK_CASE = "order-delivery-date-in-past";

interface CorpusPlacement {
  readonly kind: "GROUP" | "FIELD" | "EMPTY";
  readonly path: string;
  readonly reps: readonly number[];
  readonly value?: string;
}

type CorpusOperation =
  | {readonly kind: "validateFull"}
  | {
      readonly kind: "validatePart";
      readonly relevant: readonly {
        readonly path: string;
        readonly reps: readonly number[];
      }[];
    }
  | {readonly kind: "compute"};

interface CorpusCase {
  readonly meta: {readonly id: string};
  readonly modelRef: string;
  readonly clock?: string;
  readonly placements: readonly CorpusPlacement[];
  readonly op: CorpusOperation;
  readonly expected?: readonly string[];
  readonly computeExpected?: readonly string[];
}

export interface BrowserQualification {
  readonly corpusCases: number;
  readonly workspace: "passed";
  readonly host: "passed";
}

const fetchText = async (path: string): Promise<string> => {
  const response = await fetch(`${import.meta.env.BASE_URL}${path}`);
  if (!response.ok) {
    throw new Error(`${path} request failed with HTTP ${response.status}`);
  }
  return response.text();
};

const fetchJson = async <T>(path: string): Promise<T> =>
  JSON.parse(await fetchText(path)) as T;

const modelIdOf = (json: string): string => {
  const root = JSON.parse(json) as {readonly header?: {readonly id?: unknown}};
  if (typeof root.header?.id !== "string") {
    throw new Error("browser qualification model has no string header.id");
  }
  return root.header.id;
};

const stringFieldPathsOf = (json: string): ReadonlySet<string> => {
  const paths = new Set<string>();
  const walk = (value: unknown): void => {
    if (Array.isArray(value)) {
      value.forEach(walk);
    } else if (value !== null && typeof value === "object") {
      const object = value as Record<string, unknown>;
      if (
        object.type === "Field" &&
        typeof object.id === "string" &&
        (object.Field as {
          readonly fieldType?: {readonly type?: unknown};
        } | undefined)?.fieldType?.type === "StringType"
      ) {
        paths.add(object.id);
      }
      Object.values(object).forEach(walk);
    }
  };
  walk(JSON.parse(json));
  return paths;
};

const documentInput = (
  placements: readonly CorpusPlacement[],
): DocumentInputV1 => ({
  version: 1,
  groups: placements
    .filter(({kind}) => kind === "GROUP")
    .map(({path, reps}) => ({path, coordinates: reps})),
  fields: placements
    .filter(({kind}) => kind !== "GROUP")
    .map(({kind, path, reps, value}) => ({
      path,
      coordinates: reps,
      raw: kind === "EMPTY" ? "" : value ?? "",
    })),
});

const validationSignature = (
  finding: ValidationFinding,
): string =>
  `${finding.code}|${
    finding.type === "omission" ? "OMISSION_ERROR" : "VALUE_ERROR"
  }|${finding.errorPath}`;

const formattedPointer = (
  path: string,
  coordinates: readonly number[],
): string => {
  const segments = path.slice(1).split("/");
  if (segments.length !== coordinates.length) {
    throw new Error(
      `cannot render pointer '${path}': ${coordinates.length} coordinates`,
    );
  }
  return `/${segments.map((segment, index) =>
    index === segments.length - 1
      ? segment
      : `${segment}[${coordinates[index]}]`).join("/")}`;
};

const prepareCorpusModel = async (
  modelRef: string,
): Promise<{
  readonly prepared: PreparedModel;
  readonly stringFieldPaths: ReadonlySet<string>;
}> => {
  const modelJson = await fetchText(`corpus/${modelRef}`);
  return {
    prepared: ModelWorkspace.fromSources([{json: modelJson}])
      .prepare(modelIdOf(modelJson)),
    stringFieldPaths: stringFieldPathsOf(modelJson),
  };
};

const requireSupported = (
  label: string,
  report: {
    readonly fullySupported: boolean;
    readonly unsupported: readonly unknown[];
  },
): void => {
  if (!report.fullySupported || report.unsupported.length > 0) {
    throw new Error(`${label} reported unsupported programs`);
  }
};

const projectComputeResult = (
  result: ComputationResult,
  placements: readonly CorpusPlacement[],
  stringFieldPaths: ReadonlySet<string>,
): string | undefined => {
  const pointer = formattedPointer(
    result.pointer.path,
    result.pointer.coordinates,
  );
  const seed = placements.find((placement) =>
    placement.kind === "FIELD" &&
    placement.value !== "" &&
    formattedPointer(placement.path, placement.reps) === pointer);
  switch (result.outcome) {
    case "value":
      if (
        stringFieldPaths.has(result.pointer.path) &&
        seed?.value === result.value
      ) {
        return undefined;
      }
      return `${pointer}|VALUE|${result.value ?? ""}`;
    case "cleared":
      return seed === undefined ? undefined : `${pointer}|CLEARED`;
    case "errored":
      return `${pointer}|ERRORED`;
  }
};

const replayCorpusCase = async (
  corpusCase: CorpusCase,
): Promise<readonly string[]> => {
  const {prepared, stringFieldPaths} = await prepareCorpusModel(
    corpusCase.modelRef,
  );
  const snapshot = prepared.documents.decodeDocument(
    documentInput(corpusCase.placements),
  );
  const interpreter = prepared.createInterpreter();
  switch (corpusCase.op.kind) {
    case "validateFull": {
      const report = interpreter.validateFull(snapshot);
      requireSupported(corpusCase.meta.id, report);
      return report.findings.map(validationSignature).sort();
    }
    case "validatePart": {
      const relevant = corpusCase.op.relevant.map(({path, reps}) =>
        prepared.model.relevantPointer(path, reps));
      const report = interpreter.validatePart(snapshot, relevant);
      requireSupported(corpusCase.meta.id, report);
      return report.findings.map(validationSignature).sort();
    }
    case "compute": {
      const report = interpreter.compute(snapshot);
      requireSupported(corpusCase.meta.id, report);
      return report.results
        .map((result) =>
          projectComputeResult(
            result,
            corpusCase.placements,
            stringFieldPaths,
          ))
        .filter((signature): signature is string => signature !== undefined)
        .sort();
    }
  }
};

const assertSame = (
  label: string,
  actual: readonly string[],
  expected: readonly string[],
): void => {
  if ([...actual].sort().join("\n") !== [...expected].sort().join("\n")) {
    throw new Error(
      `${label} mismatch: actual=${JSON.stringify(actual)} ` +
      `expected=${JSON.stringify(expected)}`,
    );
  }
};

const qualifyCorpus = async (): Promise<number> => {
  const paths = await fetchJson<readonly string[]>("corpus/cases.json");
  const cases = await Promise.all(
    paths.map((path) => fetchJson<CorpusCase>(`corpus/cases/${path}`)),
  );
  // The v1 package deliberately defers an explicit evaluation clock; every other corpus case is portable.
  const portable = cases.filter((corpusCase) => corpusCase.clock === undefined);
  const deferred = cases.filter((corpusCase) => corpusCase.clock !== undefined);
  if (portable.length !== EXPECTED_CORPUS_CASES) {
    throw new Error(
      `portable browser corpus has ${portable.length} cases, ` +
      `expected ${EXPECTED_CORPUS_CASES}`,
    );
  }
  if (
    deferred.length !== 1 ||
    deferred[0]?.meta.id !== DEFERRED_CLOCK_CASE
  ) {
    throw new Error(
      `browser corpus clock exclusion changed: ${JSON.stringify(
        deferred.map(({meta}) => meta.id),
      )}`,
    );
  }
  for (const corpusCase of portable) {
    const expected = corpusCase.op.kind === "compute"
      ? corpusCase.computeExpected
      : corpusCase.expected;
    if (expected === undefined) {
      throw new Error(`corpus case '${corpusCase.meta.id}' has no anchor`);
    }
    assertSame(
      `corpus case '${corpusCase.meta.id}'`,
      await replayCorpusCase(corpusCase),
      expected,
    );
  }
  return portable.length;
};

const qualifyRawWorkspace = async (): Promise<void> => {
  const paths = [
    "entries/permit-single.dm.json",
    "shared/permit-section.dm.json",
    "shared/inspection-checks.dm.json",
    "shared/permit-types.dm.json",
  ] as const;
  const sources = await Promise.all(paths.map(async (path) => ({
    json: await fetchText(`workspace/${path}`),
    diagnosticLabel: path,
  })));
  const finite = ModelWorkspace.fromSources(sources).prepare("permit-single");
  const requested: string[] = [];
  const byId = new Map(sources.map((source) => [modelIdOf(source.json), source]));
  const collected = await ModelWorkspace.collect("permit-single", {
    snapshotRevision: "browser-r4.2",
    async load(modelId) {
      requested.push(modelId);
      return byId.get(modelId);
    },
  });
  const onDemand = collected.prepare("permit-single");
  if (onDemand.fingerprint !== finite.fingerprint) {
    throw new Error("finite and collected browser workspaces differ");
  }
  const expectedRequests = [
    "permit-single",
    "permit-types",
    "permit-section",
    "inspection-checks",
  ];
  if (requested.join("\n") !== expectedRequests.join("\n")) {
    throw new Error(
      `workspace provider request order mismatch: ` +
      `actual=${JSON.stringify(requested)} expected=${JSON.stringify(expectedRequests)}`,
    );
  }

  const snapshot = finite.documents.decodeDocument({
    version: 1,
    groups: [],
    fields: [
      {
        path: "/Permit/ApplicationCode",
        coordinates: [1, 1],
        raw: "ABCDEFGHIJKLM",
      },
      {
        path: "/Permit/Review/Inspection/Score",
        coordinates: [1, 1, 1, 1],
        raw: "-1",
      },
    ],
  });
  const findings = finite.createInterpreter().validateFull(snapshot).findings;
  assertSame(
    "raw browser workspace",
    findings.map(validationSignature),
    [
      "NEGATIVE_SCORE|VALUE_ERROR|/Permit[1]/Review[1]/Inspection[1]/Score",
      "stringZuLang|VALUE_ERROR|/Permit[1]/ApplicationCode",
    ],
  );
};

const qualifyHostBoundaries = async (): Promise<void> => {
  const modelJson = await fetchText("models/permit-basic.dm.json");
  const prepared = ModelWorkspace.fromSources([{json: modelJson}])
    .prepare("permit-basic");
  const snapshot = prepared.documents.decodeDocument({
    version: 1,
    groups: [],
    fields: [
      {path: "/Permit/Rate", coordinates: [1, 1], raw: "0.30"},
      {path: "/Permit/PatternProbe", coordinates: [1, 1], raw: "12 34"},
      {path: "/Permit/GraphemeProbe", coordinates: [1, 1], raw: "e\u0301"},
      {
        path: "/Permit/ReportedAt",
        coordinates: [1, 1],
        raw: "2024-03-31T01:30:00",
      },
      {
        path: "/Permit/SettledAt",
        coordinates: [1, 1],
        raw: "2024-03-31T03:30:00",
      },
    ],
  });
  const interpreter = prepared.createInterpreter();
  if (!interpreter.supportReport().fullySupported) {
    throw new Error("host-boundary browser model is not fully supported");
  }
  assertSame(
    "browser host boundary",
    interpreter.validateFull(snapshot).findings.map(({code}) => code).sort(),
    [
      "BERLIN_SPRING_HOUR",
      "DECIMAL_CONTROL",
      "GRAPHEME_DIALECT",
      "PATTERN_DIALECT",
    ],
  );
};

export const qualifyBrowser = async (): Promise<BrowserQualification> => {
  const corpusCases = await qualifyCorpus();
  await qualifyRawWorkspace();
  await qualifyHostBoundaries();
  return {corpusCases, workspace: "passed", host: "passed"};
};
