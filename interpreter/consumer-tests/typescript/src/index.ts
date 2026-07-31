import {readFileSync} from "node:fs";

import {
  DocumentCoordinateError,
  DocumentInputError,
  InterpreterIntegrationError,
  ModelOwnerMismatchError,
  ModelWorkspace,
  PreparedModel,
  type CustomCondition,
  type CustomFieldValidator,
  type A12Document,
  type ModelSourceProvider,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";

import {validateWithProjectCondition} from "./guide-customization.js";
import {runInterpreter} from "./guide-finite.js";
import {collectWorkspace} from "./guide-provider.js";

const model = (name: string): string =>
  readFileSync(new URL(`../models/${name}.dm.json`, import.meta.url), "utf8");

const sourcesById = new Map<string, WorkspaceModelSource>([
  ["permit-single", {json: model("permit-single"), diagnosticLabel: "permit-single"}],
  ["permit-section", {json: model("permit-section"), diagnosticLabel: "permit-section"}],
  ["inspection-checks", {json: model("inspection-checks"), diagnosticLabel: "inspection-checks"}],
  ["permit-types", {json: model("permit-types"), diagnosticLabel: "permit-types"}],
  ["permit-basic", {json: model("permit-basic"), diagnosticLabel: "permit-basic"}],
]);

const guideResult = runInterpreter(model("permit-basic"));
if (guideResult.full.findings.map(({code}) => code).join(",") !== "AREA_NEEDS_NOTE" ||
    guideResult.computation.results.find(({pointer}) =>
      pointer.path === "/Permit/ReviewedArea"
    )?.value !== "120" ||
    !guideResult.partial.fullySupported) {
  throw new Error("finite-workspace guide example returned an unexpected result");
}
const guideWorkspace = await collectWorkspace("permit-single", sourcesById);
if (guideWorkspace.prepare("permit-single").entryModelId !== "permit-single") {
  throw new Error("provider guide example did not prepare its entry model");
}

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
const seededDocument = prepared.documents.encodeDocument(
  prepared.documents.seedDocument(),
);
const seededPermit = seededDocument.Permit;
const expectedSeededFields = [
  "ApplicationNo",
  "ApplicantName",
  "PermitType",
  "SubmittedOn",
  "RequestedArea",
  "UnitFee",
  "ReviewedArea",
  "EstimatedFee",
  "Note",
  "Rate",
  "PatternProbe",
  "GraphemeProbe",
  "ReportedAt",
  "SettledAt",
  "SiteSections",
].sort();
if (
  seededPermit === null ||
  typeof seededPermit !== "object" ||
  Array.isArray(seededPermit) ||
  Object.keys(seededPermit).sort().join(",") !==
    expectedSeededFields.join(",")
) {
  throw new Error(
    `prepared Document seed omitted model fields: ${
      JSON.stringify(seededDocument)
    }`,
  );
}
const seededSections = (seededPermit as Record<string, unknown>).SiteSections;
if (
  !Array.isArray(seededSections) ||
  seededSections.length !== 1 ||
  typeof seededSections[0] !== "object" ||
  seededSections[0] === null ||
  !Object.hasOwn(seededSections[0], "SectionName") ||
  !Object.hasOwn(seededSections[0], "Area")
) {
  throw new Error(
    `prepared Document seed omitted its repeatable group row: ${
      JSON.stringify(seededDocument)
    }`,
  );
}
const input: A12Document = {
  Permit: {ApplicationNo: "P-1", RequestedArea: 125},
};
const snapshot = prepared.documents.readDocument(input);
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
if (computation.results.length !== 2 ||
    computation.results.find(({pointer}) =>
      pointer.path === "/Permit/ReviewedArea"
    )?.value !== "125" ||
    !computation.noErrorOccurred ||
    computation.formalErrorsInOperands.length !== 0 ||
    computation.results.some(({diagnostic}) => diagnostic !== undefined)) {
  throw new Error("computation did not return the expected target value");
}
const invalidOperandComputation = interpreter.compute(
  prepared.documents.readDocument({
    Permit: {ApplicationNo: "P-1", RequestedArea: "not-a-number"},
  }),
);
if (
  invalidOperandComputation.noErrorOccurred ||
  invalidOperandComputation.formalErrorsInOperands
    .map(({code}) => code).join(",") !== "zahlHatUngueltigeZeichen"
) {
  throw new Error("computation did not expose its formal operand diagnostic");
}
const invalidTargetComputation = interpreter.compute(
  prepared.documents.readDocument({
    Permit: {
      ApplicationNo: "P-1",
      RequestedArea: 1000,
      UnitFee: "9999999999999.99",
    },
  }),
);
const estimatedFeeError = invalidTargetComputation.results.find(
  ({pointer}) => pointer.path === "/Permit/EstimatedFee",
);
if (
  invalidTargetComputation.noErrorOccurred ||
  invalidTargetComputation.formalErrorsInOperands.length !== 0 ||
  estimatedFeeError?.outcome !== "errored" ||
  estimatedFeeError.diagnostic?.code !== "stelligkeitZuLang"
) {
  throw new Error("computation did not attach its target-owned diagnostic");
}
const applied = computation.applyTo(prepared.documents.readDocument({Permit: {}}));
if (prepared.documents.encodeDocumentJson(applied) !==
    '{"Permit":{"ReviewedArea":125}}' ||
    prepared.documents.encodeDocumentJson(interpreter.applyComputations(snapshot)) !==
    '{"Permit":{"ApplicationNo":"P-1","RequestedArea":125,"ReviewedArea":125}}') {
  throw new Error("retained computation application returned an unexpected Document");
}
const unchanged = interpreter.compute(prepared.documents.readDocument({
  Permit: {ApplicationNo: "P-1", RequestedArea: 125, ReviewedArea: 125},
}));
if (unchanged.actions.length !== 0) {
  throw new Error("a typed source-equal Number became a retained VALUE action");
}
try {
  computation.applyTo(preparedIncluded.documents.readDocument({}));
  throw new Error("retained computation accepted a foreign model owner");
} catch (error) {
  if (!(error instanceof ModelOwnerMismatchError)) {
    throw error;
  }
}

const canonical = prepared.documents.encodeDocumentJson(snapshot);
if (canonical !== '{"Permit":{"ApplicationNo":"P-1","RequestedArea":125}}') {
  throw new Error(`Document transport did not round-trip: ${canonical}`);
}

const unknownKey = prepared.documents.tryReadDocument({
  Permit: {Unexpected: "P-2"},
});
if (unknownKey.ok ||
    unknownKey.error.diagnostics[0]?.reasonCode !== "unknown-model-path" ||
    unknownKey.error.diagnostics[0]?.pointer !== "Permit/Unexpected") {
  throw new Error("unknown Document key did not produce a structured diagnostic");
}

try {
  prepared.documents.readDocument({Permit: {RequestedArea: Number.NaN}});
  throw new Error("Document reading accepted a value JSON cannot carry");
} catch (error) {
  if (!(error instanceof DocumentInputError) ||
      error.diagnostics[0]?.reasonCode !== "unrepresentable-native-value" ||
      error.diagnostics[0]?.inputPath?.join("/") !== "Permit/RequestedArea") {
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
  Order: {CustomerName: "ACME"},
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
const guideCustomization = validateWithProjectCondition(
  model("order-ruled"),
  {Order: {CustomerName: "ACME"}},
);
if (guideCustomization.findings.every(({code}) => code !== "INELIGIBLE_ORDER")) {
  throw new Error("customization guide example returned no authored finding");
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
