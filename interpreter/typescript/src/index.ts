export {
  DocumentEncodeError,
  DocumentInputError,
  DocumentCoordinateError,
  InterpreterIntegrationError,
  ModelOwnerMismatchError,
  ModelPreparationError,
  ModelSourceProviderError,
  UnknownModelPathError,
} from "./errors.js";
export type {
  A12Result,
  DocumentCoordinateReasonCode,
  DocumentInputDiagnostic,
  DocumentInputPathSegment,
  DocumentJsonNodeType,
  InterpreterIntegrationReason,
  ModelPreparationDiagnostic,
  ModelReferenceStep,
  ModelSourceProviderFailureReason,
} from "./errors.js";

export type {
  CustomCondition,
  CustomConditionDocumentView,
  CustomConditionInvocation,
  CustomFieldValidationParameters,
  CustomFieldValidationResult,
  CustomFieldValidator,
  InterpreterConfiguration,
} from "./customization.js";

export {
  DocumentSnapshot,
  PreparedDocumentApi,
} from "./documents.js";
export type {
  A12Document,
  A12DocumentField,
  A12DocumentGroup,
} from "./documents.js";

export {Interpreter} from "./evaluation.js";
export type {
  ComputationOutcome,
  ComputationReport,
  ComputationResult,
  ComputeOptions,
  InterpreterSupportReport,
  UnsupportedProgram,
  UnsupportedProgramSubject,
  ValidationFinding,
  ValidationFindingType,
  ValidationOptions,
  ValidationReport,
  ValidationSeverity,
} from "./evaluation.js";

export type {
  DocumentDecodeLimits,
  DocumentDecodeOptions,
  ModelPreparationLimits,
} from "./limits.js";

export {
  DocumentPointer,
  FieldPointer,
  GroupPointer,
  ModelMetadata,
  RelevantSelectionPointer,
} from "./model.js";
export {A12PointerFormat} from "./pointers.js";
export type {A12Pointer} from "./pointers.js";
export type {
  ModelEntityKind,
  RelevantCoordinate,
  RelevantEntityPointer,
} from "./model.js";

export {PreparedModel} from "./prepared-model.js";
export type {ModelDependency} from "./prepared-model.js";

export {ModelWorkspace} from "./workspace.js";
export type {
  ModelCollectionOptions,
  ModelSourceLoadContext,
  ModelSourceProvider,
  WorkspaceModelSource,
} from "./workspace.js";
