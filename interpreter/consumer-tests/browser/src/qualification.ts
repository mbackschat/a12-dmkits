import {
  type A12Document,
  type A12DocumentGroup,
  A12PartiallyKnownMultiPointer,
  type ComputationResult,
  ModelWorkspace,
  type PreparedModel,
  type ValidationFinding,
} from "@mbackschat/a12-interpreter";

const EXPECTED_CORPUS_CASES = 33;
const DEFERRED_CLOCK_CASE = "order-delivery-date-in-past";

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
  readonly document: A12Document;
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

/**
 * The document's FILLED cells, keyed by the corpus pointer key — the input side the kernel's compute reporting
 * granularity is diffed against (a value equal to its seeded input is not reported as a change).
 *
 * This walks the document the case already carries; it does not re-implement A12's shape. The converter that
 * used to live here lifted the retired `placements[]` array into A12 Document JSON and is gone with it — the
 * corpus now stores the canonical shape, so the reader under test receives it verbatim. That converter also
 * mapped an unfilled cell to `""`; the corpus encodes it as `null`, which is A12's "present with no value".
 */
const seededCells = (document: A12Document): ReadonlyMap<string, string> => {
  const seeded = new Map<string, string>();
  const walk = (
    node: A12DocumentGroup,
    path: string,
    reps: readonly number[],
  ): void => {
    for (const [name, value] of Object.entries(node)) {
      if (path === "" && name === "id") {
        continue;   // the serializer's root document identity, not a model element
      }
      const childPath = `${path}/${name}`;
      if (Array.isArray(value)) {
        value.forEach((row, index) =>
          walk(row as A12DocumentGroup, childPath, [...reps, index + 1]));
      } else if (value !== null && typeof value === "object") {
        walk(value as A12DocumentGroup, childPath, [...reps, 1]);
      } else if (value !== null && value !== "") {
        seeded.set(corpusPointerKey(childPath, [...reps, 1]), String(value));
      }
    }
  };
  walk(document as unknown as A12DocumentGroup, "", []);
  return seeded;
};

const validationSignature = (
  finding: ValidationFinding,
): string =>
  `${finding.code}|${
    finding.type === "omission" ? "OMISSION_ERROR" : "VALUE_ERROR"
  }|${housePointerKey(finding.errorPath)}`;

/**
 * The conformance corpus keys its expected outcomes by the interpreter's **house** pointer form — a leading
 * `/` and an explicit `[n]` on every non-terminal part, including `[1]`: `/Permit[1]/SiteSections[2]/Area`.
 * That is the key the JVM/Node corpus harnesses build, so the browser replay must produce it byte-for-byte or
 * the committed `computeExpected` lists stop comparing.
 *
 * <p>It is rendered from the finding's own message pointer, which is a `PartiallyKnownDocumentMultiPointer`
 * value rather than text. Routing it through `A12PointerFormat` instead would be wrong twice over: that codec
 * publishes A12's *canonical* form, which omits `[1]` and so cannot spell this key at all, and it refuses the
 * wildcard and unknown indexes a message address may legitimately carry. Only the join is local, and it is local
 * because the house form is a tool-owned corpus key, not a published A12 format.
 */
const housePointerKey = (
  pointer: A12PartiallyKnownMultiPointer,
): string => {
  const names = pointer.fullName.replace(/^\//, "").split("/");
  return names.map((name, index) =>
    index === names.length - 1
      ? `/${name}`
      : `/${name}[${pointer.repetitionIndexes[index]}]`).join("");
};

/** The same key for a document CELL the replay seeds, whose address it holds as a path plus coordinates. */
const corpusPointerKey = (
  path: string,
  coordinates: readonly number[],
): string =>
  housePointerKey(A12PartiallyKnownMultiPointer.of(path, coordinates));

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
  seeded: ReadonlyMap<string, string>,
  stringFieldPaths: ReadonlySet<string>,
): string | undefined => {
  const pointer = corpusPointerKey(
    result.pointer.path,
    result.pointer.coordinates,
  );
  const seed = seeded.get(pointer);
  switch (result.outcome) {
    case "value":
      if (
        stringFieldPaths.has(result.pointer.path) &&
        seed === result.value
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
  const snapshot = prepared.documents.readDocument(corpusCase.document);
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
      const seeded = seededCells(corpusCase.document);   // indexed once, not per result
      return report.results
        .map((result) => projectComputeResult(result, seeded, stringFieldPaths))
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

  const snapshot = finite.documents.readDocument({
    Permit: {
      ApplicationCode: "ABCDEFGHIJKLM",
      Review: {Inspection: {Score: -1}},
    },
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
  // Deliberately supplied as strings: these probes assert exact stored text on a host boundary.
  const snapshot = prepared.documents.readDocument({
    Permit: {
      ApplicationNo: "P-HOST",
      RequestedArea: 80,
      Rate: "0.30",
      PatternProbe: "12 34",
      GraphemeProbe: "e\u0301",
      ReportedAt: "2024-03-31T01:30:00",
      SettledAt: "2024-03-31T03:30:00",
    },
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
