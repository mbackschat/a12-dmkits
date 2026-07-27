import {readFileSync} from "node:fs";

import {
  DocumentCoordinateError,
  DocumentInputError,
  InterpreterIntegrationError,
  ModelWorkspace,
  PreparedModel,
  type CustomCondition,
  type CustomFieldValidator,
  type DocumentInputV1,
  type ModelSourceProvider,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";

const model = (name: string): string =>
  readFileSync(new URL(`../models/${name}.dm.json`, import.meta.url), "utf8");

const sourcesById = new Map<string, WorkspaceModelSource>([
  ["permit-single", {json: model("permit-single"), diagnosticLabel: "permit-single"}],
  ["permit-section", {json: model("permit-section"), diagnosticLabel: "permit-section"}],
  ["inspection-checks", {json: model("inspection-checks"), diagnosticLabel: "inspection-checks"}],
  ["permit-types", {json: model("permit-types"), diagnosticLabel: "permit-types"}],
  ["permit-basic", {json: model("permit-basic"), diagnosticLabel: "permit-basic"}],
]);

const finiteWorkspace = ModelWorkspace.fromSources([...sourcesById.values()]);
const expanded = finiteWorkspace.expand("permit-single");
if (expanded.includes('"modelAlias"') || expanded.includes('"TypeDefType"')) {
  throw new Error("workspace expansion retained include or typedef residue");
}
const preparedIncluded = finiteWorkspace.prepare("permit-single");
if (preparedIncluded.entryModelId !== "permit-single") {
  throw new Error(`prepared unexpected entry ${preparedIncluded.entryModelId}`);
}

const requested: string[] = [];
const controller = new AbortController();
const provider: ModelSourceProvider = {
  snapshotRevision: "typescript-consumer-v1",
  async load(modelId, context) {
    requested.push(modelId);
    if (context.signal !== controller.signal || context.maximumBytes <= 0) {
      throw new Error("collector did not forward its load context");
    }
    return sourcesById.get(modelId);
  },
};
const collected = await ModelWorkspace.collect("permit-single", provider, {
  signal: controller.signal,
});
if (collected.snapshotRevision !== "typescript-consumer-v1") {
  throw new Error("collector lost the snapshot revision");
}
collected.prepare("permit-single");
if (requested.includes("permit-basic") || new Set(requested).size !== requested.length) {
  throw new Error(`collector requested the wrong closure: ${requested.join(",")}`);
}

const prepared = PreparedModel.loadExpandedJson(
  finiteWorkspace.expand("permit-basic"),
);
const input: DocumentInputV1 = {
  version: 1,
  groups: [{path: "/Permit", coordinates: [1]}],
  fields: [
    {path: "/Permit/ApplicationNo", coordinates: [1, 1], raw: "P-1"},
    {path: "/Permit/RequestedArea", coordinates: [1, 1], raw: "125"},
  ],
};
const snapshot = prepared.documents.decodeDocument(input);
const neverUsedCondition: CustomCondition = () => false;
const neverUsedFieldValidator: CustomFieldValidator = () => ({accepted: true});
const interpreter = prepared.createInterpreter({
  customConditions: {NeverUsed: neverUsedCondition},
  customFieldTypes: {NeverUsed: neverUsedFieldValidator},
});
const validation = interpreter.validateFull(snapshot, {locale: "en_US"});
if (validation.findings.length !== 1 ||
    validation.findings[0]?.code !== "AREA_NEEDS_NOTE" ||
    validation.noErrorOccurred) {
  throw new Error("validation did not return the expected authored finding");
}
const computation = interpreter.compute(snapshot, {locale: "en_US"});
if (computation.results.length !== 1 ||
    computation.results[0]?.pointer.path !== "/Permit/ReviewedArea" ||
    computation.results[0]?.value !== "125") {
  throw new Error("computation did not return the expected target value");
}

const canonical = prepared.documents.encodeDocument(snapshot);
if (canonical.version !== 1 || canonical.fields.length !== 2) {
  throw new Error("Document transport did not round-trip");
}

const malformed = prepared.documents.tryDecodeDocument({
  version: 1,
  groups: [],
  fields: [{
    path: "/Permit/ApplicationNo",
    coordinates: [1, 1],
    raw: "P-2",
    unexpected: true,
  }],
});
if (malformed.ok ||
    malformed.error.diagnostics[0]?.reasonCode !== "unknown-property" ||
    malformed.error.diagnostics[0]?.inputPath.join("/") !== "fields/0/unexpected") {
  throw new Error("unknown Document member did not produce a structured diagnostic");
}

try {
  prepared.documents.readDocument({
    version: 1,
    groups: [{path: "/Permit", coordinates: [2_147_483_648]}],
    fields: [],
  });
  throw new Error("typed placement accepted a coordinate outside the portable range");
} catch (error) {
  if (!(error instanceof DocumentInputError) ||
      error.diagnostics[0]?.details?.reason !== "outside-portable-range") {
    throw error;
  }
}

try {
  prepared.model.relevantPointer(
    "/Permit/ApplicationNo",
    ["ALL", 2_147_483_648],
  );
  throw new Error("relevance pointer accepted a coordinate outside the portable range");
} catch (error) {
  if (!(error instanceof DocumentCoordinateError) ||
      error.reason !== "coordinate-outside-portable-range" ||
      error.coordinateIndex !== 1) {
    throw error;
  }
}

// These classes are intentionally constructible by hosts and retain stable native Error identity.
if (new InterpreterIntegrationError(
  "evaluate",
  "thenable-returned",
  "probe",
).code !== "A12_INTERPRETER_INTEGRATION_FAILED") {
  throw new Error("integration error lost its public code");
}

const customPrepared = PreparedModel.loadExpandedJson(model("order-ruled"));
const customSnapshot = customPrepared.documents.readDocument({
  version: 1,
  groups: [{path: "/Order", coordinates: [1]}],
  fields: [{path: "/Order/CustomerName", coordinates: [1, 1], raw: "ACME"}],
});
let conditionCalls = 0;
const customized = customPrepared.createInterpreter({
  customConditions: {
    ExternalEligibility(invocation) {
      conditionCalls += 1;
      if (invocation.errorPointer.path !== "/Order/CustomerName" ||
          invocation.document.value(invocation.errorPointer) !== "ACME" ||
          invocation.document.fields().length !== 1) {
        throw new Error("custom-condition invocation lost its document view");
      }
      return true;
    },
  },
});
const customizedReport = customized.validateFull(customSnapshot);
if (conditionCalls !== 1 ||
    customizedReport.findings.every(({code}) => code !== "INELIGIBLE_ORDER")) {
  throw new Error("custom condition did not reach validation");
}

const asynchronousCondition = (async () => true) as unknown as CustomCondition;
const invalidCustomization = customPrepared.createInterpreter({
  customConditions: {ExternalEligibility: asynchronousCondition},
});
try {
  invalidCustomization.validateFull(customSnapshot);
  throw new Error("an asynchronous customization callback was awaited or accepted");
} catch (error) {
  if (!(error instanceof InterpreterIntegrationError) ||
      error.reason !== "thenable-returned") {
    throw error;
  }
}

console.log("TypeScript package consumer passed");
