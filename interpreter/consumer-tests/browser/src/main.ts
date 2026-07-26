import {
  ModelWorkspace,
  type PlacementDocumentInputV1,
  type ValidationFinding,
} from "@mbackschat/a12-interpreter";

const status = document.querySelector<HTMLOutputElement>("#status");
if (!status) {
  throw new Error("browser smoke status output is missing");
}

const fail = (error: unknown): never => {
  const message = error instanceof Error ? error.message : String(error);
  status.dataset.state = "failed";
  status.textContent = message;
  throw error;
};

const run = async (): Promise<void> => {
  const modelResponse = await fetch(
    `${import.meta.env.BASE_URL}models/permit-basic.dm.json`,
  );
  if (!modelResponse.ok) {
    throw new Error(`model request failed with HTTP ${modelResponse.status}`);
  }
  const modelJson = await modelResponse.text();
  const prepared = ModelWorkspace.fromSources([{json: modelJson}])
    .prepare("permit-basic");
  const placements: PlacementDocumentInputV1 = {
    version: 1,
    groups: [{path: "/Permit", coordinates: [1]}],
    fields: [
      {path: "/Permit/ApplicationNo", coordinates: [1, 1], raw: "P-42"},
      {path: "/Permit/RequestedArea", coordinates: [1, 1], raw: "120"},
    ],
  };
  const snapshot = prepared.documents.decodePlacements(placements);
  const interpreter = prepared.createInterpreter();
  const findings: readonly ValidationFinding[] =
    interpreter.validateFull(snapshot).findings;
  if (findings.map(({code}) => code).join(",") !== "AREA_NEEDS_NOTE") {
    throw new Error("browser validation did not return AREA_NEEDS_NOTE");
  }
  const computation = interpreter.compute(snapshot).results;
  if (computation.length !== 1 ||
      computation[0]?.pointer.path !== "/Permit/ReviewedArea" ||
      computation[0]?.value !== "120") {
    throw new Error("browser computation did not return the reviewed area");
  }

  status.dataset.state = "passed";
  status.textContent = "Packed interpreter browser consumer passed";
};

void run().catch(fail);
