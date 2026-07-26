import {
  ModelSourceProviderError,
  ModelWorkspace,
  type PlacementDocumentInputV1,
  type PreparedModel,
  type RelevantCoordinate,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";

type ScenarioId =
  | "clean"
  | "firing"
  | "formal-invalid"
  | "computation"
  | "include-typedef";
type WorkspaceMode = "file-map" | "provider";
type WorkspaceId = "permit-basic" | "permit-workspace";

interface Scenario {
  readonly workspace: WorkspaceId;
  readonly entryModelId: string;
  readonly note: string;
  readonly input: PlacementDocumentInputV1;
  readonly relevantPath: string;
  readonly relevantCoordinates: readonly number[];
}

interface ReleaseIdentity {
  readonly package: string;
  readonly version: string;
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

const permitInput = (
  applicationNo: string,
  requestedArea: string,
  note?: string,
): PlacementDocumentInputV1 => ({
  version: 1,
  groups: [{path: "/Permit", coordinates: [1]}],
  fields: [
    {
      path: "/Permit/ApplicationNo",
      coordinates: [1, 1],
      raw: applicationNo,
    },
    {
      path: "/Permit/RequestedArea",
      coordinates: [1, 1],
      raw: requestedArea,
    },
    ...(note === undefined
      ? []
      : [{path: "/Permit/Note", coordinates: [1, 1], raw: note}]),
  ],
});

const scenarios: Readonly<Record<ScenarioId, Scenario>> = {
  clean: {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "A valid permit that should produce no findings.",
    input: permitInput("P-42", "80"),
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  firing: {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "A large requested area without a note fires AREA_NEEDS_NOTE.",
    input: permitInput("P-42", "120"),
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  "formal-invalid": {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "Malformed numeric text remains present and becomes a formal finding.",
    input: permitInput("P-42", "not-a-number"),
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  computation: {
    workspace: "permit-basic",
    entryModelId: "permit-basic",
    note: "The computation copies RequestedArea into ReviewedArea.",
    input: permitInput("P-42", "125", "manual review"),
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
  "include-typedef": {
    workspace: "permit-workspace",
    entryModelId: "permit-single",
    note: "The entry imports a typedef and mounts a nested inspection model.",
    input: {
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
    },
    relevantPath: "/Permit",
    relevantCoordinates: [1],
  },
};

const sourcePaths: Readonly<Record<WorkspaceId, readonly string[]>> = {
  "permit-basic": ["models/permit-basic.dm.json"],
  "permit-workspace": [
    "workspace/entries/permit-single.dm.json",
    "workspace/shared/permit-section.dm.json",
    "workspace/shared/inspection-checks.dm.json",
    "workspace/shared/permit-types.dm.json",
  ],
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
const operationButtons = Array.from(
  document.querySelectorAll<HTMLButtonElement>(".actions button"),
);

let preparedState: PreparedState | undefined;
let uploadedWorkspace: UploadedWorkspace | undefined;
let uploadRevision = 0;

const currentScenario = (): Scenario =>
  scenarios[scenarioSelect.value as ScenarioId];

const fetchText = async (path: string): Promise<string> => {
  const response = await fetch(`${import.meta.env.BASE_URL}${path}`);
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

const loadSources = async (
  workspace: WorkspaceId,
): Promise<readonly WorkspaceModelSource[]> =>
  Promise.all(sourcePaths[workspace].map(async (path) => ({
    json: await fetchText(path),
    diagnosticLabel: path,
  })));

const prepareWorkspace = async (): Promise<PreparedState> => {
  const scenario = currentScenario();
  const mode = workspaceModeSelect.value as WorkspaceMode;
  const workspaceIdentity = uploadedWorkspace === undefined
    ? scenario.workspace
    : `upload-${uploadedWorkspace.revision}`;
  const selectedEntryModelId = uploadedWorkspace === undefined
    ? scenario.entryModelId
    : entryModelSelect.value;
  const key = `${mode}:${workspaceIdentity}:${selectedEntryModelId}`;
  if (preparedState?.key === key) {
    return preparedState;
  }

  status.dataset.state = "running";
  status.textContent = "Resolving the workspace…";
  const sources = uploadedWorkspace?.sources ??
    await loadSources(scenario.workspace);
  const requests: string[] = [];
  const workspace = mode === "file-map"
    ? ModelWorkspace.fromSources(sources)
    : await ModelWorkspace.collect(selectedEntryModelId, {
      snapshotRevision: `showcase-r5.1-${workspaceIdentity}`,
      async load(modelId, context) {
        requests.push(modelId);
        if (context.signal.aborted) {
          throw new ModelSourceProviderError("cancelled", modelId);
        }
        const source = sources.find(({json}) => modelIdOf(json) === modelId);
        if (
          source !== undefined &&
          new TextEncoder().encode(source.json).byteLength >
            context.maximumBytes
        ) {
          throw new ModelSourceProviderError("source-too-large", modelId);
        }
        return source;
      },
    });
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

const selectScenario = (): void => {
  const scenario = currentScenario();
  uploadedWorkspace = undefined;
  entryModelSelect.disabled = true;
  entryModelSelect.replaceChildren(new Option("Bundled scenario entry"));
  documentInput.value = JSON.stringify(scenario.input, null, 2);
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

const loadReleaseIdentity = async (): Promise<void> => {
  const identity = JSON.parse(await fetchText("release.json")) as ReleaseIdentity;
  if (identity.package !== "@mbackschat/a12-interpreter") {
    throw new Error(`unexpected showcase package '${identity.package}'`);
  }
  requiredElement("#package-version", HTMLElement).textContent =
    identity.version;
};

export const startShowcase = async (): Promise<void> => {
  scenarioSelect.addEventListener("change", selectScenario);
  validationScopeSelect.addEventListener("change", () => {
    relevantSelection.hidden = validationScopeSelect.value !== "relevant";
  });
  workspaceModeSelect.addEventListener("change", () => {
    preparedState = undefined;
    selectScenario();
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

  selectScenario();
  await loadReleaseIdentity();
  await prepareWorkspace();
};
