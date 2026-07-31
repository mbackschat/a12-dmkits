import {
  type ComputationReport,
  type DocumentSnapshot,
  ModelWorkspace,
  type PreparedModel,
  type RelevantCoordinate,
  type ValidationReport,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";
import {
  BundledWorkspaceDelivery,
  loadShowcaseManifest,
  offlineUseWasEnabled,
  prepareOfflineShell,
  type ShowcaseManifest,
} from "./delivery.js";

type ScenarioId =
  | "clean"
  | "firing"
  | "formal-invalid"
  | "computation"
  | "computed-error"
  | "include-typedef";
type WorkspaceMode = "file-map" | "provider";
type WorkspaceId = "permit-basic" | "permit-workspace";
type DocumentOrigin = "scenario" | "seed" | "file" | "edited";
type ProviderFailure =
  | "none"
  | "integrity-mismatch"
  | "source-too-large"
  | "cancelled";

interface Scenario {
  readonly workspace: WorkspaceId;
  readonly entryModelId: string;
  readonly note: string;
  readonly inputPath: string;
  readonly relevantPath: string;
  readonly relevantCoordinates: readonly number[];
}

interface ReleaseIdentity {
  readonly package: string;
  readonly version: string;
  readonly a12Kernel: string;
  readonly a12Distribution: string;
}

interface BundleSize {
  readonly schemaVersion: 1;
  readonly asset: string;
  readonly uncompressedBytes: number;
  readonly gzipBytes: number;
}

interface PreparedState {
  readonly key: string;
  readonly shapeKey: string;
  readonly expandedModelJson: string;
  readonly model: PreparedModel;
  readonly interpreter: ReturnType<PreparedModel["createInterpreter"]>;
  readonly requests: readonly string[];
}

interface UploadedWorkspace {
  readonly sources: readonly WorkspaceModelSource[];
  readonly revision: number;
}

interface TimedOperation<T> {
  readonly value: T;
  readonly samplesMillis: readonly number[];
}

interface RetainedComputation {
  readonly state: PreparedState;
  readonly sourceJson: string;
  readonly report: ComputationReport;
}

interface ValidationOperation {
  readonly scope: "full" | "partial";
  run(snapshot: DocumentSnapshot): ValidationReport;
}

type TimedOperationName = "validation" | "computation" | "form-flow";

const MAX_OPERATION_RUNS = 100_000;

const scenarios: Readonly<Record<ScenarioId, Scenario>> = {
  clean: {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "A valid permit uses required fields, labels, an enum, a date, rules, and two computations without producing findings.",
    inputPath: "examples/documents/permit-valid.json",
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  firing: {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "A large requested area without a note fires AREA_NEEDS_NOTE.",
    inputPath: "examples/documents/permit-rule-firing.json",
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  "formal-invalid": {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "Malformed numeric text remains present and becomes a formal finding.",
    inputPath: "examples/documents/permit-formal-invalid.json",
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  computation: {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "Two computations copy RequestedArea into ReviewedArea and calculate EstimatedFee from area × unit fee.",
    inputPath: "examples/documents/permit-computation.json",
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  "computed-error": {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "Valid operands produce an EstimatedFee that exceeds the target format, so Compute attaches a target-owned diagnostic.",
    inputPath: "examples/documents/permit-computed-error.json",
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  "include-typedef": {
    workspace: "permit-workspace",
    entryModelId: "permit-single",
    note: "The entry imports a typedef and mounts a nested inspection model.",
    inputPath: "examples/documents/permit-workspace-invalid.json",
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
};

const requiredElement = <T extends Element>(
  selector: string,
  constructor: {new(): T},
): T => {
  const element = document.querySelector(selector);
  if (!(element instanceof constructor)) {
    throw new Error(`showcase element '${selector}' is missing`);
  }
  return element;
};

const status = requiredElement("#status", HTMLOutputElement);
const scenarioSelect = requiredElement("#scenario", HTMLSelectElement);
const workspaceModeSelect = requiredElement(
  "#workspace-mode",
  HTMLSelectElement,
);
const entryModelSelect = requiredElement(
  "#entry-model-select",
  HTMLSelectElement,
);
const entryModelControl = requiredElement(
  "#entry-model-control",
  HTMLLabelElement,
);
const validationScopeFull = requiredElement(
  "#validation-scope-full",
  HTMLInputElement,
);
const validationScopePartial = requiredElement(
  "#validation-scope-partial",
  HTMLInputElement,
);
const providerFailureSelect = requiredElement(
  "#provider-failure",
  HTMLSelectElement,
);
const enableOfflineButton = requiredElement(
  "#enable-offline",
  HTMLButtonElement,
);
const relevantSelection = requiredElement(
  "#relevant-selection",
  HTMLDivElement,
);
const relevantPath = requiredElement("#relevant-path", HTMLInputElement);
const relevantCoordinates = requiredElement(
  "#relevant-coordinates",
  HTMLInputElement,
);
const documentInput = requiredElement("#document-input", HTMLTextAreaElement);
const seedDocumentButton = requiredElement(
  "#seed-document",
  HTMLButtonElement,
);
const applyComputationsButton = requiredElement(
  "#apply-computations",
  HTMLButtonElement,
);
const scenarioNote = requiredElement("#scenario-note", HTMLSpanElement);
const modelJson = requiredElement("#model-json", HTMLPreElement);
const result = requiredElement("#result", HTMLPreElement);
const resultSummary = requiredElement("#result-summary", HTMLDivElement);
const operationRuns = requiredElement("#operation-runs", HTMLInputElement);
const operationTiming = requiredElement(
  "#operation-timing",
  HTMLOutputElement,
);
const summary = requiredElement("#workspace-summary", HTMLElement);
const entryModel = requiredElement("#entry-model", HTMLElement);
const dependencyCount = requiredElement("#dependency-count", HTMLElement);
const providerRequests = requiredElement("#provider-requests", HTMLElement);
const fingerprint = requiredElement("#fingerprint", HTMLElement);
const manifestRevision = requiredElement("#manifest-revision", HTMLElement);
const bundleSize = requiredElement("#bundle-size", HTMLElement);
const bundleSizeUncompressed = requiredElement(
  "#bundle-size-uncompressed",
  HTMLElement,
);
const livePerformance = requiredElement(
  "#live-performance",
  HTMLOutputElement,
);
const operationButtons = Array.from(
  document.querySelectorAll<HTMLButtonElement>(
    ".actions button, .model-workspace-actions button",
  ),
);
const fileInputs = Array.from(
  document.querySelectorAll<HTMLInputElement>('input[type="file"]'),
);
const operationControls: readonly (
  | HTMLButtonElement
  | HTMLInputElement
  | HTMLSelectElement
  | HTMLTextAreaElement
)[] = [
  ...operationButtons,
  ...fileInputs,
  seedDocumentButton,
  operationRuns,
  scenarioSelect,
  workspaceModeSelect,
  validationScopeFull,
  validationScopePartial,
  providerFailureSelect,
  relevantPath,
  relevantCoordinates,
  documentInput,
];

let preparedState: PreparedState | undefined;
let uploadedWorkspace: UploadedWorkspace | undefined;
let uploadRevision = 0;
let bundledManifest: ShowcaseManifest | undefined;
let bundledDelivery: BundledWorkspaceDelivery | undefined;
let documentOrigin: DocumentOrigin = "scenario";
let documentShapeKey: string | undefined;
let retainedComputation: RetainedComputation | undefined;

const currentScenario = (): Scenario =>
  scenarios[scenarioSelect.value as ScenarioId];

const invalidateRetainedComputation = (): void => {
  retainedComputation = undefined;
  applyComputationsButton.disabled = true;
};

const invalidatePreparedState = (): void => {
  invalidateRetainedComputation();
  preparedState = undefined;
  modelJson.textContent = "Prepare a workspace to inspect its DocumentModel.";
};

const fetchText = async (path: string): Promise<string> => {
  const response = await fetch(`${import.meta.env.BASE_URL}${path}`, {
    cache: "no-store",
    credentials: "omit",
  });
  if (!response.ok) {
    throw new Error(`${path} request failed with HTTP ${response.status}`);
  }
  return response.text();
};

const modelIdOf = (json: string): string => {
  const parsed = JSON.parse(json) as {readonly header?: {readonly id?: unknown}};
  if (typeof parsed.header?.id !== "string") {
    throw new Error("showcase model has no string header.id");
  }
  return parsed.header.id;
};

const seedDocumentInput = (state: PreparedState): unknown => {
  invalidateRetainedComputation();
  const document = state.model.documents.encodeDocument(
    state.model.documents.seedDocument(),
  );
  documentInput.value = JSON.stringify(document, null, 2);
  documentOrigin = "seed";
  documentShapeKey = state.shapeKey;
  return document;
};

const prepareWorkspace = async (): Promise<PreparedState> => {
  const scenario = currentScenario();
  const mode = workspaceModeSelect.value as WorkspaceMode;
  const failure = providerFailureSelect.value as ProviderFailure;
  const workspaceIdentity = uploadedWorkspace === undefined
    ? scenario.workspace
    : `upload-${uploadedWorkspace.revision}`;
  const selectedEntryModelId = uploadedWorkspace === undefined
    ? scenario.entryModelId
    : entryModelSelect.value;
  const shapeKey = `${workspaceIdentity}:${selectedEntryModelId}`;
  const key = `${mode}:${workspaceIdentity}:${selectedEntryModelId}:${failure}`;
  if (preparedState?.key === key) {
    return preparedState;
  }

  status.dataset.state = "running";
  status.textContent = "Resolving the workspace…";
  const delivery = bundledDelivery;
  if (delivery === undefined) {
    throw new Error("bundled workspace delivery is not initialized");
  }
  const requests: string[] = [];
  const abortController = new AbortController();
  const collectionOptions = failure === "source-too-large"
    ? {
      signal: abortController.signal,
      limits: {maxModelBytes: 64},
    }
    : {signal: abortController.signal};
  const workspace = mode === "file-map"
    ? ModelWorkspace.fromSources(
      uploadedWorkspace?.sources ?? await delivery.loadAll(),
    )
    : await ModelWorkspace.collect(selectedEntryModelId, {
      snapshotRevision: delivery.revision,
      async load(modelId, context) {
        requests.push(modelId);
        const source = uploadedWorkspace === undefined
          ? await delivery.load(modelId, context)
          : uploadedWorkspace.sources.find(({json}) =>
            modelIdOf(json) === modelId);
        if (source !== undefined && failure === "integrity-mismatch") {
          return {...source, expectedSha256: "0".repeat(64)};
        }
        if (failure === "cancelled" && requests.length === 1) {
          abortController.abort();
        }
        return source;
      },
    }, collectionOptions);
  const expandedModelJson = workspace.expand(selectedEntryModelId);
  const model = workspace.prepare(selectedEntryModelId);
  preparedState = {
    key,
    shapeKey,
    expandedModelJson,
    model,
    interpreter: model.createInterpreter(),
    requests,
  };
  renderSummary(selectedEntryModelId, preparedState);
  if (
    (documentOrigin === "scenario" || documentOrigin === "seed") &&
    documentShapeKey !== shapeKey
  ) {
    seedDocumentInput(preparedState);
  }
  status.dataset.state = "passed";
  status.textContent = "Workspace prepared. The model and interpreter are reusable.";
  return preparedState;
};

const renderSummary = (
  selectedEntryModelId: string,
  state: PreparedState,
): void => {
  entryModel.textContent = selectedEntryModelId;
  dependencyCount.textContent = state.model.dependencies.length.toString();
  providerRequests.textContent = state.requests.length === 0
    ? "not used"
    : state.requests.join(" → ");
  fingerprint.textContent = state.model.fingerprint;
  summary.dataset.fingerprint = state.model.fingerprint;
  summary.dataset.requestCount = state.requests.length.toString();
  summary.dataset.manifestRevision = bundledDelivery?.revision;
  modelJson.textContent = JSON.stringify(
    JSON.parse(state.expandedModelJson) as unknown,
    null,
    2,
  );
  manifestRevision.textContent = uploadedWorkspace === undefined
    ? bundledDelivery?.revision ?? "—"
    : "local files";
};

const renderResult = (operation: string, value: unknown): void => {
  result.dataset.operation = operation;
  result.textContent = JSON.stringify(value, null, 2);
};

const summarizeResult = (
  label: string,
  count: number,
  noun: string,
  nonzeroTone: "success" | "attention" = "attention",
): void => {
  resultSummary.dataset.tone = count === 0 ? "success" : nonzeroTone;
  resultSummary.textContent = `${label}: ${count} ${noun}${
    count === 1 ? "" : "s"
  }. Complete structured output follows.`;
  resultSummary.dataset.count = count.toString();
};

const resetOperationTiming = (): void => {
  operationTiming.hidden = true;
  operationTiming.textContent = "";
  delete operationTiming.dataset.samples;
  delete operationTiming.dataset.metric;
  delete operationTiming.dataset.operation;
  delete operationTiming.dataset.millis;
};

const selectedOperationRuns = (): number => {
  const runs = operationRuns.valueAsNumber;
  if (
    !Number.isSafeInteger(runs) ||
    runs < 1 ||
    runs > MAX_OPERATION_RUNS
  ) {
    throw new Error(
      `Runs must be a whole number from 1 to ${MAX_OPERATION_RUNS}.`,
    );
  }
  return runs;
};

const timeOperation = <T>(
  runs: number,
  operation: () => T,
): TimedOperation<T> => {
  let startedAt = performance.now();
  let value = operation();
  const samplesMillis = [performance.now() - startedAt];
  for (let index = 1; index < runs; index += 1) {
    startedAt = performance.now();
    value = operation();
    samplesMillis.push(performance.now() - startedAt);
  }
  return {value, samplesMillis};
};

const p95Of = (samplesMillis: readonly number[]): number => {
  const ordered = [...samplesMillis].sort((left, right) => left - right);
  return ordered[Math.ceil(ordered.length * 0.95) - 1]!;
};

const renderOperationTiming = (
  operation: TimedOperationName,
  samplesMillis: readonly number[],
): void => {
  const metric = samplesMillis.length === 1 ? "elapsed" : "p95";
  const milliseconds = metric === "elapsed"
    ? samplesMillis[0]!
    : p95Of(samplesMillis);
  const label = operation === "validation"
    ? "Validation"
    : operation === "computation"
    ? "Computation"
    : "Compute → Apply → Validate";
  const runs = samplesMillis.length === 1
    ? "one run"
    : `${samplesMillis.length} runs`;
  operationTiming.hidden = false;
  operationTiming.dataset.samples = samplesMillis.length.toString();
  operationTiming.dataset.metric = metric;
  operationTiming.dataset.operation = operation;
  operationTiming.dataset.millis = milliseconds.toString();
  operationTiming.textContent =
    `${label}: ${milliseconds.toFixed(2)} ms${
      metric === "p95" ? " p95" : ""
    } · ${runs} · same prepared interpreter and Document.`;
};

const renderError = (error: unknown): void => {
  const exposed = error instanceof Error
    ? {
      name: error.name,
      code: "code" in error ? error.code : undefined,
      message: error.message,
      diagnostics: "diagnostics" in error ? error.diagnostics : undefined,
    }
    : {message: String(error)};
  renderResult("error", exposed);
  resultSummary.dataset.tone = "attention";
  resultSummary.textContent = "The operation failed. Complete structured diagnostics follow.";
  delete resultSummary.dataset.count;
  status.dataset.state = "failed";
  status.textContent = error instanceof Error ? error.message : String(error);
};

const withOperation = async (
  operation: () => Promise<void>,
): Promise<void> => {
  resetOperationTiming();
  operationControls.forEach((control) => {
    control.disabled = true;
  });
  try {
    await operation();
  } catch (error) {
    renderError(error);
  } finally {
    operationControls.forEach((control) => {
      control.disabled = false;
    });
    applyComputationsButton.disabled = retainedComputation === undefined;
  }
};

const snapshot = async (): Promise<{
  readonly scenario: Scenario;
  readonly state: PreparedState;
  readonly value: ReturnType<PreparedModel["documents"]["readDocument"]>;
}> => {
  const scenario = currentScenario();
  const state = await prepareWorkspace();
  return {
    scenario,
    state,
    value: state.model.documents.readDocument(documentInput.value),
  };
};

const selectedValidation = (state: PreparedState): ValidationOperation => {
  if (validationScopeFull.checked) {
    return {
      scope: "full",
      run: (document) => state.interpreter.validateFull(document),
    };
  }
  const relevant = [
    state.model.model.relevantPointer(
      relevantPath.value,
      selectedRelevantCoordinates(),
    ),
  ];
  return {
    scope: "partial",
    run: (document) => state.interpreter.validatePart(document, relevant),
  };
};

const computationResultsOutput = (
  entries: ComputationReport["results"],
) =>
  entries.map((entry) => ({
    pointer: {
      path: entry.pointer.path,
      coordinates: entry.pointer.coordinates,
    },
    outcome: entry.outcome,
    value: entry.value,
    diagnostic: entry.diagnostic,
  }));

const computationOutput = (report: ComputationReport) => ({
  results: computationResultsOutput(report.results),
  actions: computationResultsOutput(report.actions),
  formalErrorsInOperands: report.formalErrorsInOperands,
  noErrorOccurred: report.noErrorOccurred,
  unsupported: report.unsupported,
  fullySupported: report.fullySupported,
});

const selectScenario = async (): Promise<void> => {
  const scenario = currentScenario();
  uploadedWorkspace = undefined;
  entryModelControl.hidden = true;
  entryModelSelect.disabled = true;
  entryModelSelect.replaceChildren(new Option("Bundled scenario entry"));
  documentInput.value = JSON.stringify(
    JSON.parse(await fetchText(scenario.inputPath)) as unknown,
    null,
    2,
  );
  documentOrigin = "scenario";
  documentShapeKey = `${scenario.workspace}:${scenario.entryModelId}`;
  scenarioNote.textContent = scenario.note;
  relevantPath.value = scenario.relevantPath;
  relevantCoordinates.value = JSON.stringify(scenario.relevantCoordinates);
  invalidatePreparedState();
  entryModel.textContent = scenario.entryModelId;
  dependencyCount.textContent = "—";
  providerRequests.textContent = "—";
  fingerprint.textContent = "—";
  delete summary.dataset.fingerprint;
  delete summary.dataset.requestCount;
};

const selectedRelevantCoordinates = (): readonly RelevantCoordinate[] => {
  const parsed: unknown = JSON.parse(relevantCoordinates.value);
  if (
    !Array.isArray(parsed) ||
    !parsed.every((coordinate) =>
      coordinate === "ALL" ||
      (typeof coordinate === "number" &&
        Number.isSafeInteger(coordinate) &&
        coordinate > 0))
  ) {
    throw new Error(
      "relevant coordinates must be a JSON array of positive integers or \"ALL\"",
    );
  }
  return parsed as readonly RelevantCoordinate[];
};

const loadWorkspaceFiles = async (files: FileList): Promise<void> => {
  const sources = await Promise.all(Array.from(files, async (file) => ({
    json: await file.text(),
    diagnosticLabel: file.webkitRelativePath || file.name,
  })));
  const admitted = ModelWorkspace.fromSources(sources);
  uploadRevision += 1;
  uploadedWorkspace = {sources, revision: uploadRevision};
  invalidatePreparedState();
  entryModelSelect.replaceChildren(
    ...admitted.modelIds.map((modelId) => new Option(modelId, modelId)),
  );
  entryModelSelect.disabled = false;
  entryModelControl.hidden = false;
  scenarioNote.textContent =
    `Loaded ${sources.length} workspace file${sources.length === 1 ? "" : "s"} locally. Select the entry model, then prepare.`;
  status.dataset.state = "passed";
  status.textContent = "Workspace files loaded locally.";
};

const loadDocumentFile = async (file: File): Promise<void> => {
  const parsed: unknown = JSON.parse(await file.text());
  invalidateRetainedComputation();
  documentInput.value = JSON.stringify(parsed, null, 2);
  documentOrigin = "file";
  documentShapeKey = undefined;
  status.dataset.state = "passed";
  status.textContent = "Document loaded locally and remains editable.";
};

const loadReleaseIdentity = async (): Promise<ReleaseIdentity> => {
  const identity = JSON.parse(await fetchText("release.json")) as ReleaseIdentity;
  if (identity.package !== "@mbackschat/a12-interpreter") {
    throw new Error(`unexpected showcase package '${identity.package}'`);
  }
  requiredElement("#package-version", HTMLElement).textContent =
    identity.version;
  requiredElement("#semantics-target", HTMLElement).textContent =
    `A12 Kernel ${identity.a12Kernel} · A12 Tools ${identity.a12Distribution}`;
  return identity;
};

const loadBundleSize = async (): Promise<void> => {
  const measured = JSON.parse(
    await fetchText("bundle-size.json"),
  ) as Partial<BundleSize>;
  if (
    measured.schemaVersion !== 1 ||
    typeof measured.asset !== "string" ||
    !measured.asset.startsWith("assets/") ||
    !measured.asset.endsWith(".js") ||
    typeof measured.gzipBytes !== "number" ||
    typeof measured.uncompressedBytes !== "number" ||
    !Number.isSafeInteger(measured.gzipBytes) ||
    !Number.isSafeInteger(measured.uncompressedBytes) ||
    measured.gzipBytes <= 0 ||
    measured.gzipBytes >= measured.uncompressedBytes
  ) {
    throw new Error("showcase bundle-size measurement is invalid");
  }
  bundleSize.textContent = `${Math.round(measured.gzipBytes / 1_000)} kB gzip`;
  bundleSize.dataset.gzipBytes = measured.gzipBytes.toString();
  bundleSize.dataset.uncompressedBytes = measured.uncompressedBytes.toString();
  bundleSizeUncompressed.textContent =
    `${Math.round(measured.uncompressedBytes / 1_000)} kB`;
};

const measureRepresentativePerformance = async (): Promise<void> => {
  if (
    new URLSearchParams(location.search).get("skip-live-performance") === "1"
  ) {
    livePerformance.textContent = "not measured";
    livePerformance.dataset.state = "skipped";
    return;
  }
  try {
    const {
      BrowserPerformanceScenario,
      measureBrowserFirstAnswer,
    } = await import("./performance.js");
    const measurement = await measureBrowserFirstAnswer(
      BrowserPerformanceScenario.REPRESENTATIVE,
    );
    livePerformance.textContent = `${measurement.elapsedMillis.toFixed(1)} ms`;
    livePerformance.dataset.state = "passed";
    livePerformance.dataset.documentBytes =
      measurement.documentBytes.toString();
  } catch {
    livePerformance.textContent = "unavailable";
    livePerformance.dataset.state = "failed";
  }
};

const exampleDocumentPaths = (): readonly string[] =>
  Object.values(scenarios).map(({inputPath}) => inputPath);

const markOfflineReady = (): void => {
  enableOfflineButton.disabled = true;
  enableOfflineButton.textContent = "Offline use enabled";
  status.dataset.offlineReady = "true";
  status.dataset.state = "passed";
  status.textContent = "Offline use enabled for the built-in showcase files.";
};

const enableOfflineUse = async (): Promise<void> => {
  const manifest = bundledManifest;
  if (manifest === undefined) {
    throw new Error("showcase manifest is not initialized");
  }
  enableOfflineButton.disabled = true;
  status.dataset.state = "running";
  status.textContent = "Preparing built-in files for offline use…";
  try {
    await prepareOfflineShell(manifest.revision, exampleDocumentPaths());
    const offlineDelivery = new BundledWorkspaceDelivery(manifest, true);
    await offlineDelivery.loadAll();
    bundledDelivery = offlineDelivery;
    markOfflineReady();
  } catch (error) {
    enableOfflineButton.disabled = false;
    throw error;
  }
};

export const startShowcase = async (): Promise<void> => {
  documentInput.addEventListener("input", () => {
    invalidateRetainedComputation();
    documentOrigin = "edited";
  });
  scenarioSelect.addEventListener("change", () => {
    void withOperation(selectScenario);
  });
  for (const scope of [validationScopeFull, validationScopePartial]) {
    scope.addEventListener("change", () => {
      relevantSelection.hidden = validationScopeFull.checked;
    });
  }
  workspaceModeSelect.addEventListener("change", () => {
    invalidatePreparedState();
  });
  providerFailureSelect.addEventListener("change", () => {
    if (providerFailureSelect.value !== "none") {
      workspaceModeSelect.value = "provider";
    }
    invalidatePreparedState();
  });
  entryModelSelect.addEventListener("change", () => {
    invalidatePreparedState();
  });
  enableOfflineButton.addEventListener("click", () => {
    void withOperation(enableOfflineUse);
  });
  seedDocumentButton.addEventListener("click", () => {
    void withOperation(async () => {
      const state = await prepareWorkspace();
      const document = seedDocumentInput(state);
      renderResult("seed-document", document);
      resultSummary.dataset.tone = "success";
      resultSummary.textContent =
        "Created a model-shaped Document candidate with every declared field and group.";
      delete resultSummary.dataset.count;
      status.dataset.state = "passed";
      status.textContent = "Model-shaped Document starter created.";
    });
  });
  for (const selector of ["#workspace-folder-upload", "#workspace-upload"]) {
    requiredElement(selector, HTMLInputElement).addEventListener(
      "change",
      (event) => {
        const input = event.currentTarget as HTMLInputElement;
        if (input.files !== null && input.files.length > 0) {
          void withOperation(() => loadWorkspaceFiles(input.files!));
        }
      },
    );
  }
  requiredElement("#document-upload", HTMLInputElement).addEventListener(
    "change",
    (event) => {
      const input = event.currentTarget as HTMLInputElement;
      const file = input.files?.item(0);
      if (file !== null && file !== undefined) {
        void withOperation(() => loadDocumentFile(file));
      }
    },
  );
  requiredElement("#prepare", HTMLButtonElement).addEventListener(
    "click",
    () => void withOperation(async () => {
      const state = await prepareWorkspace();
      renderResult("prepare", {
        entryModelId: state.model.entryModelId,
        fingerprint: state.model.fingerprint,
        dependencies: state.model.dependencies,
        providerRequests: state.requests,
      });
      resultSummary.dataset.tone = "success";
      resultSummary.textContent =
        `Prepared ${state.model.entryModelId} with ${state.model.dependencies.length} resolved dependencies.`;
      resultSummary.dataset.count =
        state.model.dependencies.length.toString();
    }),
  );
  requiredElement("#check-support", HTMLButtonElement).addEventListener(
    "click",
    () => void withOperation(async () => {
      const state = await prepareWorkspace();
      const report = state.interpreter.supportReport();
      renderResult("support-report", report);
      summarizeResult(
        "Model support checked",
        report.unsupported.length,
        "unsupported program",
        report.fullySupported ? "success" : "attention",
      );
      status.dataset.state = "passed";
      status.textContent = report.fullySupported
        ? "The prepared model is fully supported."
        : "The prepared model contains unsupported programs.";
    }),
  );
  requiredElement("#validate", HTMLButtonElement).addEventListener(
    "click",
    () => void withOperation(async () => {
      const document = await snapshot();
      const runs = selectedOperationRuns();
      const validation = selectedValidation(document.state);
      const timed = timeOperation(runs, () => validation.run(document.value));
      const report = timed.value;
      renderResult(`validate-${validation.scope}`, report);
      summarizeResult("Validation complete", report.findings.length, "finding");
      renderOperationTiming("validation", timed.samplesMillis);
      status.dataset.state = "passed";
      status.textContent = validation.scope === "full"
        ? "Full validation completed."
        : "Partial validation completed.";
    }),
  );
  requiredElement("#compute", HTMLButtonElement).addEventListener(
    "click",
    () => void withOperation(async () => {
      const document = await snapshot();
      const timed = timeOperation(
        selectedOperationRuns(),
        () => document.state.interpreter.compute(document.value),
      );
      const report = timed.value;
      retainedComputation = {
        state: document.state,
        sourceJson: documentInput.value,
        report,
      };
      renderResult("compute", computationOutput(report));
      summarizeResult(
        "Computation complete",
        report.results.length,
        "result",
        "success",
      );
      renderOperationTiming("computation", timed.samplesMillis);
      status.dataset.state = "passed";
      status.textContent =
        "Computation completed. Its retained actions can now be applied.";
    }),
  );
  applyComputationsButton.addEventListener(
    "click",
    () => void withOperation(async () => {
      const retained = retainedComputation;
      if (retained === undefined) {
        throw new Error("Compute the current Document before applying results.");
      }
      const document = await snapshot();
      if (
        retained.state !== document.state ||
        retained.sourceJson !== documentInput.value
      ) {
        invalidateRetainedComputation();
        throw new Error(
          "The model or Document changed after computation; compute it again before applying.",
        );
      }
      const applied = retained.report.applyTo(document.value);
      const encoded = document.state.model.documents.encodeDocument(applied);
      documentInput.value = JSON.stringify(encoded, null, 2);
      documentOrigin = "edited";
      documentShapeKey = document.state.shapeKey;
      const appliedActions = retained.report.actions.length;
      invalidateRetainedComputation();
      renderResult("apply-computations", {
        document: encoded,
        appliedActions,
      });
      summarizeResult(
        "Computation applied",
        appliedActions,
        "action",
        "success",
      );
      status.dataset.state = "passed";
      status.textContent =
        "Retained computation actions were applied to the editable Document.";
    }),
  );
  requiredElement(
    "#compute-apply-validate",
    HTMLButtonElement,
  ).addEventListener(
    "click",
    () => void withOperation(async () => {
      const document = await snapshot();
      const validation = selectedValidation(document.state);
      const timed = timeOperation(selectedOperationRuns(), () => {
        const computation = document.state.interpreter.compute(document.value);
        const applied = computation.applyTo(document.value);
        return {
          computation,
          applied,
          validation: validation.run(applied),
        };
      });
      const encoded = document.state.model.documents.encodeDocument(
        timed.value.applied,
      );
      documentInput.value = JSON.stringify(encoded, null, 2);
      documentOrigin = "edited";
      documentShapeKey = document.state.shapeKey;
      invalidateRetainedComputation();
      renderResult(`compute-apply-validate-${validation.scope}`, {
        document: encoded,
        computation: {
          ...computationOutput(timed.value.computation),
          appliedActions: timed.value.computation.actions.length,
        },
        validation: timed.value.validation,
      });
      summarizeResult(
        "Computed, applied, and validated",
        timed.value.validation.findings.length,
        "finding",
      );
      renderOperationTiming("form-flow", timed.samplesMillis);
      status.dataset.state = "passed";
      status.textContent = validation.scope === "full"
        ? "Computations applied and full validation completed."
        : "Computations applied and partial validation completed.";
    }),
  );

  await Promise.all([loadReleaseIdentity(), loadBundleSize()]);
  const offlineEnabled = offlineUseWasEnabled();
  bundledManifest = await loadShowcaseManifest(offlineEnabled);
  bundledDelivery = new BundledWorkspaceDelivery(
    bundledManifest,
    offlineEnabled,
  );
  await selectScenario();
  await prepareWorkspace();
  void measureRepresentativePerformance();
  if (offlineEnabled) {
    await prepareOfflineShell(
      bundledDelivery.revision,
      exampleDocumentPaths(),
    );
    markOfflineReady();
  }
};
