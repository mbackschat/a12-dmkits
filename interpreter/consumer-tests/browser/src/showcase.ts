import {
  ModelWorkspace,
  type PreparedModel,
  type RelevantCoordinate,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";
import {
  BundledWorkspaceDelivery,
  loadShowcaseManifest,
  prepareOfflineShell,
} from "./delivery.js";

type ScenarioId =
  | "clean"
  | "firing"
  | "formal-invalid"
  | "computation"
  | "include-typedef";
type WorkspaceMode = "file-map" | "provider";
type WorkspaceId = "permit-basic" | "permit-workspace";
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
}

interface BundleSize {
  readonly schemaVersion: 1;
  readonly asset: string;
  readonly uncompressedBytes: number;
  readonly gzipBytes: number;
}

interface PreparedState {
  readonly key: string;
  readonly model: PreparedModel;
  readonly interpreter: ReturnType<PreparedModel["createInterpreter"]>;
  readonly requests: readonly string[];
}

interface UploadedWorkspace {
  readonly sources: readonly WorkspaceModelSource[];
  readonly revision: number;
}

const scenarios: Readonly<Record<ScenarioId, Scenario>> = {
  clean: {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "A valid permit that should produce no findings.",
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
    note: "The computation copies RequestedArea into ReviewedArea.",
    inputPath: "examples/documents/permit-computation.json",
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
const validationScopeSelect = requiredElement(
  "#validation-scope",
  HTMLSelectElement,
);
const providerFailureSelect = requiredElement(
  "#provider-failure",
  HTMLSelectElement,
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
const scenarioNote = requiredElement("#scenario-note", HTMLSpanElement);
const result = requiredElement("#result", HTMLPreElement);
const resultSummary = requiredElement("#result-summary", HTMLDivElement);
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
const operationButtons = Array.from(
  document.querySelectorAll<HTMLButtonElement>(".actions button"),
);

let preparedState: PreparedState | undefined;
let uploadedWorkspace: UploadedWorkspace | undefined;
let uploadRevision = 0;
let bundledDelivery: BundledWorkspaceDelivery | undefined;

const currentScenario = (): Scenario =>
  scenarios[scenarioSelect.value as ScenarioId];

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
  const model = workspace.prepare(selectedEntryModelId);
  preparedState = {
    key,
    model,
    interpreter: model.createInterpreter(),
    requests,
  };
  renderSummary(selectedEntryModelId, preparedState);
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
): void => {
  resultSummary.dataset.tone = count === 0 ? "success" : "attention";
  resultSummary.textContent = `${label}: ${count} ${noun}${
    count === 1 ? "" : "s"
  }. Complete structured output follows.`;
  resultSummary.dataset.count = count.toString();
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
  operationButtons.forEach((button) => {
    button.disabled = true;
  });
  try {
    await operation();
  } catch (error) {
    renderError(error);
  } finally {
    operationButtons.forEach((button) => {
      button.disabled = false;
    });
  }
};

const snapshot = async (): Promise<{
  readonly scenario: Scenario;
  readonly state: PreparedState;
  readonly value: ReturnType<PreparedModel["documents"]["decodePlacements"]>;
}> => {
  const scenario = currentScenario();
  const state = await prepareWorkspace();
  const parsed: unknown = JSON.parse(documentInput.value);
  return {
    scenario,
    state,
    value: state.model.documents.decodePlacements(parsed),
  };
};

const selectScenario = async (): Promise<void> => {
  const scenario = currentScenario();
  uploadedWorkspace = undefined;
  entryModelSelect.disabled = true;
  entryModelSelect.replaceChildren(new Option("Bundled scenario entry"));
  documentInput.value = JSON.stringify(
    JSON.parse(await fetchText(scenario.inputPath)) as unknown,
    null,
    2,
  );
  scenarioNote.textContent = scenario.note;
  relevantPath.value = scenario.relevantPath;
  relevantCoordinates.value = JSON.stringify(scenario.relevantCoordinates);
  preparedState = undefined;
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
  preparedState = undefined;
  entryModelSelect.replaceChildren(
    ...admitted.modelIds.map((modelId) => new Option(modelId, modelId)),
  );
  entryModelSelect.disabled = false;
  scenarioNote.textContent =
    `Loaded ${sources.length} workspace file${sources.length === 1 ? "" : "s"} locally. Select the entry model, then prepare.`;
  status.dataset.state = "passed";
  status.textContent = "Workspace files loaded locally.";
};

const loadDocumentFile = async (file: File): Promise<void> => {
  const parsed: unknown = JSON.parse(await file.text());
  documentInput.value = JSON.stringify(parsed, null, 2);
  status.dataset.state = "passed";
  status.textContent = "Placement document loaded locally and remains editable.";
};

const loadReleaseIdentity = async (): Promise<ReleaseIdentity> => {
  const identity = JSON.parse(await fetchText("release.json")) as ReleaseIdentity;
  if (identity.package !== "@mbackschat/a12-interpreter") {
    throw new Error(`unexpected showcase package '${identity.package}'`);
  }
  requiredElement("#package-version", HTMLElement).textContent =
    identity.version;
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

export const startShowcase = async (): Promise<void> => {
  scenarioSelect.addEventListener("change", () => {
    void withOperation(selectScenario);
  });
  validationScopeSelect.addEventListener("change", () => {
    relevantSelection.hidden = validationScopeSelect.value !== "relevant";
  });
  workspaceModeSelect.addEventListener("change", () => {
    preparedState = undefined;
  });
  providerFailureSelect.addEventListener("change", () => {
    preparedState = undefined;
  });
  entryModelSelect.addEventListener("change", () => {
    preparedState = undefined;
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
  requiredElement("#validate", HTMLButtonElement).addEventListener(
    "click",
    () => void withOperation(async () => {
      const document = await snapshot();
      const validationScope = validationScopeSelect.value;
      const report = validationScope === "full"
        ? document.state.interpreter.validateFull(document.value)
        : document.state.interpreter.validatePart(document.value, [
          document.state.model.model.relevantPointer(
            relevantPath.value,
            selectedRelevantCoordinates(),
          ),
        ]);
      renderResult(`validate-${validationScope}`, report);
      summarizeResult("Validation complete", report.findings.length, "finding");
      status.dataset.state = "passed";
      status.textContent = validationScope === "full"
        ? "Full validation completed."
        : "Relevant validation completed.";
    }),
  );
  requiredElement("#compute", HTMLButtonElement).addEventListener(
    "click",
    () => void withOperation(async () => {
      const document = await snapshot();
      const report = document.state.interpreter.compute(document.value);
      renderResult("compute", {
        ...report,
        results: report.results.map((entry) => ({
          pointer: {
            path: entry.pointer.path,
            coordinates: entry.pointer.coordinates,
          },
          outcome: entry.outcome,
          value: entry.value,
        })),
      });
      summarizeResult("Computation complete", report.results.length, "result");
      status.dataset.state = "passed";
      status.textContent = "Computation completed.";
    }),
  );

  await Promise.all([loadReleaseIdentity(), loadBundleSize()]);
  bundledDelivery = new BundledWorkspaceDelivery(
    await loadShowcaseManifest(),
  );
  await selectScenario();
  await prepareWorkspace();
  await prepareOfflineShell(
    bundledDelivery.revision,
    Object.values(scenarios).map(({inputPath}) => inputPath),
  );
  status.dataset.offlineReady = "true";
};
