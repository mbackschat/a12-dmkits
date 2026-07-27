import type {InterpreterBridgeFailure} from "../internal/a12-interpreter-core.mjs";

/** Success or one native typed error without throwing. */
export type A12Result<T, E extends Error> =
  | {readonly ok: true; readonly value: T}
  | {readonly ok: false; readonly error: E};

/** Stable category supplied when an application-owned model provider cannot return a source. */
export type ModelSourceProviderFailureReason =
  | "access-denied"
  | "unavailable"
  | "cancelled"
  | "source-too-large"
  | "integrity-mismatch"
  | "provider-failed";

/** Application-owned acquisition failure with a stable reason and requested model id. */
export class ModelSourceProviderError extends Error {
  readonly code = "A12_MODEL_SOURCE_PROVIDER_FAILED" as const;

  constructor(
    readonly reason: ModelSourceProviderFailureReason,
    readonly modelId: string,
    options?: ErrorOptions,
  ) {
    super(`provider could not load model '${modelId}'`, options);
    this.name = "ModelSourceProviderError";
  }
}

/** One directed include or imported-type-definition edge in a diagnostic chain. */
export interface ModelReferenceStep {
  readonly fromModelId: string;
  readonly toModelId: string;
  readonly purpose: "include" | "type-definitions";
  readonly alias?: string;
}

/** Structured, privacy-safe detail for one model preparation failure. */
export interface ModelPreparationDiagnostic {
  readonly code: string;
  readonly diagnosticLabel?: string;
  readonly requestedModelId?: string;
  readonly actualModelId?: string;
  readonly providerFailureReason?: ModelSourceProviderFailureReason;
  readonly expectedSha256?: string;
  readonly actualSha256?: string;
  readonly modelId?: string;
  readonly path?: string;
  readonly reference?: string;
  readonly referenceChain?: readonly ModelReferenceStep[];
  readonly conflictingDiagnosticLabels?: readonly string[];
  readonly resource?: string;
  readonly maximum?: number;
  readonly actual?: number;
  readonly firstSourceModelId?: string;
  readonly conflictingSourceModelId?: string;
  readonly sourceLanguage?: string;
  readonly entryLanguage?: string;
  readonly reason?: string;
  readonly cyclic?: boolean;
  readonly message: string;
}

/** Aggregate source-admission, collection, expansion, or preparation failure. */
export class ModelPreparationError extends Error {
  readonly code = "A12_MODEL_PREPARATION_FAILED" as const;

  constructor(readonly diagnostics: readonly ModelPreparationDiagnostic[], message: string) {
    super(message);
    this.name = "ModelPreparationError";
  }
}

/** One object-key or array-index segment locating invalid Document input. */
export type DocumentInputPathSegment = string | number;
/** Whether a Document diagnostic refers to a group-row or field-cell entry. */
export type DocumentPlacementKind = "group" | "field";
/** Why one supplied coordinate cannot be represented by the portable Document boundary. */
export type InvalidCoordinateReason =
  | "not-number"
  | "not-safe-integer"
  | "not-positive"
  | "outside-portable-range";

/** Structured detail for one invalid part of supplied Document input. */
export interface DocumentInputDiagnostic {
  readonly reasonCode: string;
  readonly inputPath: readonly DocumentInputPathSegment[];
  readonly modelPath?: string;
  readonly placementKind?: DocumentPlacementKind;
  readonly placementIndex?: number;
  readonly coordinateIndex?: number;
  readonly message: string;
  readonly details?: Readonly<Record<string, unknown>>;
}

/** Aggregate Document decoding failure produced before a snapshot is created. */
export class DocumentInputError extends Error {
  readonly code = "A12_DOCUMENT_INPUT_INVALID" as const;

  constructor(readonly diagnostics: readonly DocumentInputDiagnostic[], message?: string) {
    super(message ?? diagnostics.map(({message: text}) => text).join("; "));
    this.name = "DocumentInputError";
  }
}

/** A snapshot or pointer belongs to another prepared model. */
export class ModelOwnerMismatchError extends Error {
  readonly code = "A12_MODEL_OWNER_MISMATCH" as const;

  constructor(message = "the supplied value belongs to a different prepared model") {
    super(message);
    this.name = "ModelOwnerMismatchError";
  }
}

/** A requested pointer path names no declared group or field. */
export class UnknownModelPathError extends Error {
  readonly code = "A12_UNKNOWN_MODEL_PATH" as const;

  constructor(readonly path: string, message = `no field or group exists at '${path}'`) {
    super(message);
    this.name = "UnknownModelPathError";
  }
}

/** Stable reason why a concrete or partial-validation pointer cannot be constructed. */
export type DocumentCoordinateReasonCode =
  | "coordinate-count-mismatch"
  | "coordinate-not-positive"
  | "coordinate-outside-portable-range"
  | "field-terminal-not-one";

/** Invalid model-owned pointer coordinates. */
export class DocumentCoordinateError extends Error {
  readonly code = "A12_DOCUMENT_COORDINATE_INVALID" as const;

  constructor(
    readonly reason: DocumentCoordinateReasonCode,
    readonly path: string,
    readonly coordinateIndex: number | undefined,
    readonly expectedCoordinateCount: number | undefined,
    readonly actualCoordinateCount: number | undefined,
    message: string,
  ) {
    super(message);
    this.name = "DocumentCoordinateError";
  }
}

/** A project callback failed, re-entered evaluation, or returned asynchronously. */
export class InterpreterIntegrationError extends Error {
  readonly code = "A12_INTERPRETER_INTEGRATION_FAILED" as const;

  constructor(
    readonly phase: "bind" | "evaluate",
    readonly reason: InterpreterIntegrationReason,
    message: string,
  ) {
    super(message);
    this.name = "InterpreterIntegrationError";
  }
}

/** Stable category for a project customization integration failure. */
export type InterpreterIntegrationReason =
  | "factory-threw"
  | "callback-threw"
  | "reentrant-evaluation"
  | "thenable-returned";

const frozenArray = <T>(values: readonly T[]): readonly T[] =>
  Object.freeze([...values]);

const freezeDiagnostic = (
  diagnostic: ModelPreparationDiagnostic,
): ModelPreparationDiagnostic =>
  Object.freeze({
    ...diagnostic,
    ...(diagnostic.referenceChain === undefined
      ? {}
      : {
          referenceChain: frozenArray(
            diagnostic.referenceChain.map((step) => Object.freeze({...step})),
          ),
        }),
    ...(diagnostic.conflictingDiagnosticLabels === undefined
      ? {}
      : {conflictingDiagnosticLabels: frozenArray(diagnostic.conflictingDiagnosticLabels)}),
  });

const inputPathFor = (
  diagnostic: Record<string, unknown>,
): readonly DocumentInputPathSegment[] => {
  const kind = diagnostic.placementKind;
  const index = diagnostic.placementIndex;
  if ((kind === "group" || kind === "field") && typeof index === "number") {
    const base: DocumentInputPathSegment[] = [
      kind === "group" ? "groups" : "fields",
      index,
    ];
    if (typeof diagnostic.coordinateIndex === "number") {
      return Object.freeze([...base, "coordinates", diagnostic.coordinateIndex]);
    }
    if (diagnostic.reasonCode === "unknown-model-path") {
      return Object.freeze([...base, "path"]);
    }
    return Object.freeze(base);
  }
  if (diagnostic.reasonCode === "unsupported-version") {
    return Object.freeze(["version"]);
  }
  return Object.freeze([]);
};

const documentDiagnostic = (
  diagnostic: Record<string, unknown>,
): DocumentInputDiagnostic => {
  const {
    reasonCode,
    modelPath,
    placementKind,
    placementIndex,
    coordinateIndex,
    message,
    ...details
  } = diagnostic;
  return Object.freeze({
    reasonCode: String(reasonCode),
    inputPath: inputPathFor(diagnostic),
    ...(typeof modelPath === "string" ? {modelPath} : {}),
    ...(placementKind === "group" || placementKind === "field" ? {placementKind} : {}),
    ...(typeof placementIndex === "number" ? {placementIndex} : {}),
    ...(typeof coordinateIndex === "number" ? {coordinateIndex} : {}),
    message: String(message),
    details: Object.freeze(details),
  });
};

const errorFromBridgeFailure = (
  failure: InterpreterBridgeFailure,
): Error => {
  const details = JSON.parse(failure.detailsJson) as Record<string, unknown>;
  switch (failure.code) {
    case "A12_MODEL_PREPARATION_FAILED": {
      const diagnostics = Array.isArray(details.diagnostics)
        ? details.diagnostics as ModelPreparationDiagnostic[]
        : [];
      return new ModelPreparationError(
        frozenArray(diagnostics.map(freezeDiagnostic)),
        failure.message,
      );
    }
    case "A12_DOCUMENT_INPUT_INVALID": {
      const diagnostics = Array.isArray(details.diagnostics)
        ? details.diagnostics as Record<string, unknown>[]
        : [];
      return new DocumentInputError(
        frozenArray(diagnostics.map(documentDiagnostic)),
        failure.message,
      );
    }
    case "A12_MODEL_OWNER_MISMATCH":
      return new ModelOwnerMismatchError(failure.message);
    case "A12_UNKNOWN_MODEL_PATH":
      return new UnknownModelPathError(String(details.path), failure.message);
    case "A12_DOCUMENT_COORDINATE_INVALID":
      return new DocumentCoordinateError(
        String(details.reason) as DocumentCoordinateReasonCode,
        String(details.path),
        typeof details.coordinateIndex === "number"
          ? details.coordinateIndex
          : undefined,
        typeof details.expectedCoordinateCount === "number"
          ? details.expectedCoordinateCount
          : undefined,
        typeof details.actualCoordinateCount === "number"
          ? details.actualCoordinateCount
          : undefined,
        failure.message,
      );
    case "A12_INTERPRETER_INTEGRATION_FAILED":
      return new InterpreterIntegrationError(
        String(details.phase) as "bind" | "evaluate",
        String(details.reason) as InterpreterIntegrationReason,
        failure.message,
      );
    default:
      return new Error(`${failure.code}: ${failure.message}`);
  }
};

/** @internal */
export const throwBridgeFailure = (
  failure: InterpreterBridgeFailure | null | undefined,
): never => {
  if (failure === null || failure === undefined) {
    throw new Error("interpreter bridge returned no failure details");
  }
  throw errorFromBridgeFailure(failure);
};

/** @internal */
export const resultOf = <T, E extends Error>(
  action: () => T,
  errorType: abstract new (...args: never[]) => E,
): A12Result<T, E> => {
  try {
    return Object.freeze({ok: true as const, value: action()});
  } catch (error) {
    if (error instanceof errorType) {
      return Object.freeze({ok: false as const, error});
    }
    throw error;
  }
};
