import {readFileSync} from "node:fs";

import {
  A12PartiallyKnownMultiPointer,
  A12PointerFormat,
  DocumentCoordinateError,
  DocumentInputError,
  InterpreterIntegrationError,
  ModelOwnerMismatchError,
  ModelWorkspace,
  PreparedModel,
  type CustomCondition,
  type CustomFieldValidator,
  type A12Document,
  type DocumentDecodeLimits,
  type ModelPreparationLimits,
  type ModelSourceProvider,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";
import type {A12Pointer} from "@mbackschat/a12-interpreter";

import {
  assertBoundaryClassifications,
  assertHostileClassificationsExecuted,
  deriveInboundNumberPaths,
  installedInterpreterDeclaration,
  type BoundaryClassification,
} from "./boundary-contract.js";
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

const perRowComputation = interpreter.compute(prepared.documents.readDocument({
  Permit: {
    ApplicationNo: "P-1",
    SiteSections: [
      {SectionName: "Retail floor", Area: 75},
      {SectionName: "Storage area", Area: 50},
    ],
  },
}));
const perRowCharges = perRowComputation.results.filter(
  ({pointer}) => pointer.path === "/Permit/SiteSections/SurveyCharge",
);
const reportedFinding = validation.findings[0];
if (reportedFinding === undefined) {
  throw new Error("the fixture must report a finding, or the message-channel checks below prove nothing");
}

// tag::interpreter-typescript-pointer[]
// A computation inside a repeatable group reports one result per row, all under the SAME `pointer.path`.
// `A12PointerFormat.format` joins path and coordinates into A12's canonical EXACT pointer, so each row becomes
// addressable. A12's message addressing is a different type — `PartiallyKnownDocumentMultiPointer`, which may
// carry a wildcard at any part or the unknown index — and a validation finding CARRIES that type: `errorPath`,
// `referenced` and `fillToFix` are values, not strings, so nothing has been rendered away. It converts to an
// exact pointer only when it really names one cell.
const identifiedRows = perRowCharges.map(({pointer, value}) =>
  `${A12PointerFormat.format(pointer.path, pointer.coordinates)}=${value}`);
const errorCell = reportedFinding.errorPath;
const errorCellExact = A12PartiallyKnownMultiPointer.toExactPointer(errorCell);
// end::interpreter-typescript-pointer[]

if (perRowCharges.length !== 2 ||
    identifiedRows.join(",") !==
      "Permit/SiteSections/SurveyCharge=1875,Permit/SiteSections[2]/SurveyCharge=1250") {
  throw new Error("a repeated computed target did not carry one identified result per row");
}
// The terminal `1` is exactly what the interpreter's house form (`/Permit[1]/Note`) does not write, so reading it
// back off the published value is what proves the channel carries the pointer rather than a rendering of it.
if (errorCell.fullName !== "/Permit/Note" ||
    errorCell.repetitionIndexes.join(",") !== "1,1" ||
    errorCellExact === null ||
    errorCellExact.pathParts.map((part) => part.name).join("/") !== "Permit/Note" ||
    A12PointerFormat.format(
      errorCellExact.fullName,
      errorCellExact.repetitionIndexes,
    ) !== "Permit/Note") {
  throw new Error("a finding's message pointer did not convert to the exact pointer it names");
}
if (!Object.isFrozen(errorCell) || !Object.isFrozen(errorCell.repetitionIndexes) ||
    !Object.isFrozen(reportedFinding.referenced) || !Object.isFrozen(reportedFinding.fillToFix) ||
    reportedFinding.referenced.some((pointer) => !Object.isFrozen(pointer) ||
      !Object.isFrozen(pointer.repetitionIndexes))) {
  throw new Error("a published message pointer or repetition list was not frozen");
}
if (A12PointerFormat.LAST_PART_ZERO !== 0 ||
    A12PartiallyKnownMultiPointer.WILDCARD !== 0 ||
    A12PartiallyKnownMultiPointer.UNKNOWN !== -5) {
  throw new Error("the pointer types do not publish A12's reserved repetitions");
}
// The two domains really are different: the unknown index is a message value and nothing else.
const unresolved = A12PartiallyKnownMultiPointer.of("/Plan/Demand/Headcount", [1, -5, 1]);
if (unresolved.repetitionIndexes.join(",") !== "1,-5,1" ||
    A12PartiallyKnownMultiPointer.toExactPointer(unresolved) !== null) {
  throw new Error("a message pointer with A12's unknown index did not read as unresolved");
}
try {
  A12PointerFormat.parse("Plan/Demand[-5]/Headcount");
  throw new Error("the exact pointer type accepted A12's unknown repetition");
} catch (error) {
  if (!(error instanceof TypeError)) {
    throw error;
  }
}
try {
  A12PointerFormat.parse("Permit/SiteSections[0]/Area");
  throw new Error("the exact pointer accepted a non-final zero repetition");
} catch (error) {
  if (!(error instanceof TypeError)) {
    throw error;
  }
}
// …while the message channel's wildcard IS legal at every part.
if (A12PartiallyKnownMultiPointer.of("/Permit/SiteSections/Area", [1, 0, 1])
      .repetitionIndexes.join(",") !== "1,0,1") {
  throw new Error("a message pointer refused the wildcard A12 allows at every part");
}
// The ROOT conversion raises, exactly as A12's own toDocumentPointer() does.
try {
  A12PartiallyKnownMultiPointer.toExactPointer(A12PartiallyKnownMultiPointer.of("", []));
  throw new Error("the root conversion answered where A12 raises");
} catch (error) {
  if (!(error instanceof TypeError)) {
    throw error;
  }
}
// A12's PathPart accepts any non-empty name; only the string CODEC restricts what it will write.
if (A12PartiallyKnownMultiPointer.of("/Order$/Order Item", [1, 2]).fullName !== "/Order$/Order Item") {
  throw new Error("the message pointer refused a fullName its owner accepts");
}
try {
  A12PointerFormat.format("/Order$", [1]);
  throw new Error("the string codec wrote a name it cannot read back");
} catch (error) {
  if (!(error instanceof TypeError)) {
    throw error;
  }
}
try {
  A12PointerFormat.format("/Permit/Note", [1]);
  throw new Error("the pointer utility accepted a coordinate count it cannot pair");
} catch (error) {
  if (!(error instanceof TypeError)) {
    throw error;
  }
}
// ---- NOMINALITY LOCKS -------------------------------------------------------------------------------
// The two pointer types must not be interchangeable in EITHER direction. These are compile-time assertions:
// `@ts-expect-error` fails the build when the error it expects does NOT occur, so deleting the brands or
// re-widening a signature turns each of these into "Unused '@ts-expect-error' directive".
const exactForNominality: A12Pointer = A12PointerFormat.parse("Permit/Note");
const messageForNominality: A12PartiallyKnownMultiPointer =
  A12PartiallyKnownMultiPointer.of("/Permit/Note", [1, 1]);

// @ts-expect-error an exact pointer is not a message pointer
const notAMessagePointer: A12PartiallyKnownMultiPointer = exactForNominality;
// @ts-expect-error a message pointer is not an exact pointer
const notAnExactPointer: A12Pointer = messageForNominality;
// @ts-expect-error toExactPointer takes the message type only
const notConvertible = A12PartiallyKnownMultiPointer.toExactPointer(exactForNominality);

// …and the SAME nominality holds for a pointer that arrived through a validation finding, which is the path a
// real consumer takes. A brand applied only by the hand-built factory would pass the three checks above and
// still leave every published finding structurally typed.
const fromFinding: A12PartiallyKnownMultiPointer = reportedFinding.errorPath;
const referencedFromFinding: readonly A12PartiallyKnownMultiPointer[] = reportedFinding.referenced;
// @ts-expect-error a finding's address is a message pointer, never an exact one
const findingIsNotExact: A12Pointer = reportedFinding.errorPath;
void notAMessagePointer;
void notAnExactPointer;
void notConvertible;
void fromFinding;
void referencedFromFinding;
void findingIsNotExact;

const canonical = prepared.documents.encodeDocumentJson(snapshot);
if (canonical !== '{"Permit":{"ApplicationNo":"P-1","RequestedArea":125}}') {
  throw new Error(`Document transport did not round-trip: ${canonical}`);
}

const unknownKey = prepared.documents.tryReadDocument(
  '{"Permit":[null,{"ApplicationNo":"P-2","Unexpected":"discarded"}]}',
);
if (!unknownKey.ok || prepared.documents.encodeDocumentJson(unknownKey.value) !== '{"Permit":{"ApplicationNo":"P-2"}}') {
  throw new Error("unknown Document key was not discarded by canonical conversion");
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

const boundaryClassifications = {
  "A12PartiallyKnownMultiPointer.of.repetitionIndexes[]": {
    disposition: "hostile-packed-test",
    reason: "Kotlin/JS erases Int on a caller-authored message pointer",
  },
  "A12PartiallyKnownMultiPointer.toExactPointer.pointer.repetitionIndexes[]": {
    disposition: "hostile-packed-test",
    reason: "plain JavaScript can forge the otherwise branded pointer object",
  },
  "A12PointerFormat.format.repetitions[]": {
    disposition: "hostile-packed-test",
    reason: "Kotlin/JS erases Int on an exact pointer codec call",
  },
  "DocumentCoordinateError.constructor.actualCoordinateCount": {
    disposition: "safe",
    reason: "host-authored Error detail is data, not a validated coordinate",
  },
  "DocumentCoordinateError.constructor.coordinateIndex": {
    disposition: "safe",
    reason: "host-authored Error detail is data, not a validated coordinate",
  },
  "DocumentCoordinateError.constructor.expectedCoordinateCount": {
    disposition: "safe",
    reason: "host-authored Error detail is data, not a validated coordinate",
  },
  "DocumentInputError.constructor.diagnostics[].inputPath[]": {
    disposition: "safe",
    reason: "a numeric input-path segment is an authored array index, not a domain integer",
  },
  "Interpreter.validatePart.relevant[].coordinates[]": {
    disposition: "safe",
    reason: "package-owned pointer instances carry coordinates validated by their factories",
  },
  "ModelMetadata.pointer.coordinates[]": {
    disposition: "hostile-packed-test",
    reason: "caller-authored exact coordinates must be positive portable integers",
  },
  "ModelMetadata.relevantPointer.coordinates[]": {
    disposition: "hostile-packed-test",
    reason: "caller-authored concrete coordinates must be positive portable integers",
  },
  "ModelPreparationError.constructor.diagnostics[].actual": {
    disposition: "safe",
    reason: "host-authored Error detail records an observation and has no numeric domain",
  },
  "ModelPreparationError.constructor.diagnostics[].maximum": {
    disposition: "safe",
    reason: "host-authored Error detail records an observation and has no numeric domain",
  },
  "PreparedModel.createInterpreter.configuration.customConditions.*.invocation.document.value.pointer.coordinates[]": {
    disposition: "safe",
    reason: "the callback can return only a package-owned FieldPointer whose bridge identity and coordinates were validated at its factory",
  },
  "ModelWorkspace.collect.options.limits.maxModelBytes": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.collect.options.limits.maxModelCount": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.collect.options.limits.maxPreparedBytes": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.collect.options.limits.maxPreparedElements": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.collect.options.limits.maxReferenceDepth": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.collect.options.limits.maxReferenceEdges": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.collect.options.limits.maxStructureDepth": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.collect.options.limits.maxTotalBytes": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxModelBytes": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxModelCount": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxPreparedBytes": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxPreparedElements": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxReferenceDepth": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxReferenceEdges": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxStructureDepth": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "ModelWorkspace.fromSources.limits.maxTotalBytes": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.readDocument.document.*": {
    disposition: "hostile-packed-test",
    reason: "native JavaScript values must survive JSON serialization without loss or substitution",
  },
  "PreparedDocumentApi.readDocument.options.limits.maxDocumentCodeUnits": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.readDocument.options.limits.maxFieldPlacements": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.readDocument.options.limits.maxGroupPlacements": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.readDocument.options.limits.maxModelNestingDepth": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.readDocument.options.limits.maxPathCodeUnits": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.readDocument.options.limits.maxRawCodeUnitsPerField": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.readDocument.options.limits.maxTotalRawCodeUnits": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.tryReadDocument.document.*": {
    disposition: "hostile-packed-test",
    reason: "the result-returning route must reject the same unrepresentable native values",
  },
  "PreparedDocumentApi.tryReadDocument.options.limits.maxDocumentCodeUnits": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.tryReadDocument.options.limits.maxFieldPlacements": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.tryReadDocument.options.limits.maxGroupPlacements": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.tryReadDocument.options.limits.maxModelNestingDepth": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.tryReadDocument.options.limits.maxPathCodeUnits": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.tryReadDocument.options.limits.maxRawCodeUnitsPerField": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
  "PreparedDocumentApi.tryReadDocument.options.limits.maxTotalRawCodeUnits": {
    disposition: "hostile-packed-test",
    reason: "resource ceilings must be positive portable integers before bridge serialization",
  },
} as const satisfies Readonly<Record<string, BoundaryClassification>>;

assertBoundaryClassifications(
  deriveInboundNumberPaths(installedInterpreterDeclaration()),
  boundaryClassifications,
);

const hostileEvidence: string[] = [];
const portableIntegerHostiles = [
  -2_147_483_649,
  -1,
  1.5,
  Number.NaN,
  Number.NEGATIVE_INFINITY,
  Number.POSITIVE_INFINITY,
  2_147_483_648,
  Number.MAX_VALUE,
] as const;
const positivePortableIntegerHostiles = [0, ...portableIntegerHostiles] as const;

const expectThrows = (
  label: string,
  expected: (error: unknown) => boolean,
  action: () => unknown,
): void => {
  try {
    action();
  } catch (error) {
    if (expected(error)) {
      return;
    }
    throw error;
  }
  throw new Error(`${label} accepted a hostile packed-artifact input`);
};

const expectRejects = async (
  label: string,
  expected: (error: unknown) => boolean,
  action: () => Promise<unknown>,
): Promise<void> => {
  try {
    await action();
  } catch (error) {
    if (expected(error)) {
      return;
    }
    throw error;
  }
  throw new Error(`${label} accepted a hostile packed-artifact input`);
};

if (A12PointerFormat.format("/Permit/Note", [1, 0]) !== "Permit/Note[0]") {
  throw new Error("exact pointer packed-boundary legal control failed");
}
for (const hostile of portableIntegerHostiles) {
  expectThrows(
    `A12PointerFormat.format repetition ${hostile}`,
    (error) => error instanceof TypeError,
    () => A12PointerFormat.format("/Permit/Note", [1, hostile]),
  );
}
hostileEvidence.push("A12PointerFormat.format.repetitions[]");

const legalMessagePointer = A12PartiallyKnownMultiPointer.of("/Permit/Note", [1, -5]);
if (A12PartiallyKnownMultiPointer.toExactPointer(legalMessagePointer) !== null) {
  throw new Error("partially-known pointer packed-boundary legal control failed");
}
for (const hostile of portableIntegerHostiles) {
  expectThrows(
    `A12PartiallyKnownMultiPointer.of repetition ${hostile}`,
    (error) => error instanceof TypeError,
    () => A12PartiallyKnownMultiPointer.of("/Permit/Note", [1, hostile]),
  );
  expectThrows(
    `A12PartiallyKnownMultiPointer.toExactPointer repetition ${hostile}`,
    (error) => error instanceof TypeError,
    () => A12PartiallyKnownMultiPointer.toExactPointer({
      fullName: "/Permit/Note",
      repetitionIndexes: [1, hostile],
    } as unknown as A12PartiallyKnownMultiPointer),
  );
}
hostileEvidence.push(
  "A12PartiallyKnownMultiPointer.of.repetitionIndexes[]",
  "A12PartiallyKnownMultiPointer.toExactPointer.pointer.repetitionIndexes[]",
);

prepared.model.pointer("/Permit/Note", [1, 1]);
prepared.model.relevantPointer("/Permit/Note", ["ALL", 1]);
for (const hostile of positivePortableIntegerHostiles) {
  expectThrows(
    `ModelMetadata.pointer coordinate ${hostile}`,
    (error) => error instanceof DocumentCoordinateError,
    () => prepared.model.pointer("/Permit/Note", [1, hostile]),
  );
  expectThrows(
    `ModelMetadata.relevantPointer coordinate ${hostile}`,
    (error) => error instanceof DocumentCoordinateError,
    () => prepared.model.relevantPointer("/Permit/Note", ["ALL", hostile]),
  );
}
hostileEvidence.push(
  "ModelMetadata.pointer.coordinates[]",
  "ModelMetadata.relevantPointer.coordinates[]",
);

const preparationLimitNames = [
  "maxModelCount",
  "maxModelBytes",
  "maxTotalBytes",
  "maxPreparedBytes",
  "maxReferenceDepth",
  "maxReferenceEdges",
  "maxStructureDepth",
  "maxPreparedElements",
] as const satisfies readonly (keyof ModelPreparationLimits)[];
const maximumPreparationLimits = Object.fromEntries(
  preparationLimitNames.map((name) => [name, 2_147_483_647]),
) as ModelPreparationLimits;
ModelWorkspace.fromSources([...sourcesById.values()], maximumPreparationLimits);
await ModelWorkspace.collect("permit-single", provider, {
  limits: maximumPreparationLimits,
  signal: controller.signal,
});
for (const name of preparationLimitNames) {
  for (const hostile of positivePortableIntegerHostiles) {
    expectThrows(
      `ModelWorkspace.fromSources ${name}=${hostile}`,
      (error) => error instanceof RangeError,
      () => ModelWorkspace.fromSources([...sourcesById.values()], {[name]: hostile}),
    );
    await expectRejects(
      `ModelWorkspace.collect ${name}=${hostile}`,
      (error) => error instanceof RangeError,
      () => ModelWorkspace.collect("permit-single", provider, {
        limits: {[name]: hostile},
        signal: controller.signal,
      }),
    );
  }
  hostileEvidence.push(
    `ModelWorkspace.fromSources.limits.${name}`,
    `ModelWorkspace.collect.options.limits.${name}`,
  );
}

const documentLimitNames = [
  "maxDocumentCodeUnits",
  "maxGroupPlacements",
  "maxFieldPlacements",
  "maxPathCodeUnits",
  "maxRawCodeUnitsPerField",
  "maxTotalRawCodeUnits",
  "maxModelNestingDepth",
] as const satisfies readonly (keyof DocumentDecodeLimits)[];
const maximumDocumentLimits = Object.fromEntries(
  documentLimitNames.map((name) => [name, 2_147_483_647]),
) as DocumentDecodeLimits;
prepared.documents.readDocument({}, {limits: maximumDocumentLimits});
if (!prepared.documents.tryReadDocument({}, {limits: maximumDocumentLimits}).ok) {
  throw new Error("tryReadDocument packed-boundary legal limit control failed");
}
for (const name of documentLimitNames) {
  for (const hostile of positivePortableIntegerHostiles) {
    expectThrows(
      `PreparedDocumentApi.readDocument ${name}=${hostile}`,
      (error) => error instanceof RangeError,
      () => prepared.documents.readDocument({}, {limits: {[name]: hostile}}),
    );
    expectThrows(
      `PreparedDocumentApi.tryReadDocument ${name}=${hostile}`,
      (error) => error instanceof RangeError,
      () => prepared.documents.tryReadDocument({}, {limits: {[name]: hostile}}),
    );
  }
  hostileEvidence.push(
    `PreparedDocumentApi.readDocument.options.limits.${name}`,
    `PreparedDocumentApi.tryReadDocument.options.limits.${name}`,
  );
}

prepared.documents.readDocument({Permit: {RequestedArea: 1.5}});
if (!prepared.documents.tryReadDocument({Permit: {RequestedArea: 1.5}}).ok) {
  throw new Error("tryReadDocument packed-boundary legal finite-number control failed");
}
const unrepresentableValues: readonly unknown[] = [
  Number.NaN,
  Number.NEGATIVE_INFINITY,
  Number.POSITIVE_INFINITY,
  undefined,
  () => undefined,
  Symbol("not-json"),
  1n,
];
for (const hostile of unrepresentableValues) {
  const document = {Permit: {RequestedArea: hostile}} as unknown as A12Document;
  expectThrows(
    `PreparedDocumentApi.readDocument native ${typeof hostile}`,
    (error) => error instanceof DocumentInputError &&
      error.diagnostics[0]?.reasonCode === "unrepresentable-native-value",
    () => prepared.documents.readDocument(document),
  );
  const result = prepared.documents.tryReadDocument(document);
  if (result.ok || result.error.diagnostics[0]?.reasonCode !== "unrepresentable-native-value") {
    throw new Error(`tryReadDocument accepted or misclassified native ${typeof hostile}`);
  }
}
hostileEvidence.push(
  "PreparedDocumentApi.readDocument.document.*",
  "PreparedDocumentApi.tryReadDocument.document.*",
);

// `Today` reads the pinned instant as wall time in the model's zone: 22:30 UTC on 20 October is the 21st in Berlin.
const renewal = PreparedModel.loadExpandedJson(JSON.stringify({
  header: {id: "renewal"},
  content: {
    modelConfig: {timeZone: "Europe/Berlin"},
    modelRoot: {rootGroups: [{
      type: "Group", id: "/Renewal", name: "Renewal",
      Group: {elements: [
        {type: "Field", id: "/Renewal/DueOn", name: "DueOn",
          Field: {fieldType: {type: "DateType", DateType: {format: "yyyy-MM-dd"}}}},
        {type: "Rule", id: "/Renewal/DueToday", name: "DueToday",
          Rule: {errorEntityRelPath: "../DueOn", errorCode: "DUE_TODAY",
            errorCondition: "FieldFilled(DueOn) And [DueOn] == Today", severity: "ERROR",
            errorMessage: [{locale: "en_US", text: "The policy renews today."}]}},
      ]},
    }]},
  },
}));
const renewalDue = renewal.documents.readDocument({Renewal: {DueOn: "2026-10-21"}});
const pinnedCodes = renewal.createInterpreter({evaluationInstant: new Date("2026-10-20T22:30:00Z")})
  .validateFull(renewalDue).findings.map(({code}) => code).join(",");
if (pinnedCodes !== "DUE_TODAY") {
  throw new Error(`a pinned evaluation instant was not read in the model's zone: ${pinnedCodes}`);
}
if (!renewal.createInterpreter().supportReport().fullySupported) {
  throw new Error("the default evaluation instant left Today unsupported");
}
expectThrows(
  "createInterpreter with an invalid evaluation instant",
  (error) => error instanceof TypeError,
  () => renewal.createInterpreter({evaluationInstant: new Date(Number.NaN)}),
);

assertHostileClassificationsExecuted(boundaryClassifications, hostileEvidence);

console.log("TypeScript package consumer passed");
