import type {InterpreterBridgeFailure} from "../internal/a12-interpreter-core.mjs";

import type {ModelEntityKind} from "./model.js";

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

/** One object-key or array-index segment locating a supplied value that JSON cannot carry. */
export type DocumentInputPathSegment = string | number;
/** What a JSON node at one document position actually is, when the model expected something else. */
export type DocumentJsonNodeType = "object" | "array" | "value" | "null";

/** Structured detail for one invalid part of supplied Document input. */
export interface DocumentInputDiagnostic {
  readonly reasonCode: string;
  readonly message: string;
  /** Canonical A12 `DocumentPointer` string of the offending document position. */
  readonly pointer?: string;
  readonly modelPath?: string;
  readonly entityKind?: ModelEntityKind;
  readonly nodeType?: DocumentJsonNodeType;
  /** Path into the supplied JavaScript value, when the value itself is not JSON-representable. */
  readonly inputPath?: readonly DocumentInputPathSegment[];
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

/**
 * The document holds state A12 Document JSON has no shape for — a non-repeatable group row above 1 — so
 * encoding it would silently lose that row.
 */
export class DocumentEncodeError extends Error {
  readonly code = "A12_DOCUMENT_NOT_REPRESENTABLE" as const;

  constructor(readonly modelPath: string, readonly pointer: string, message?: string) {
    super(message ?? `'${pointer}' cannot be written as A12 Document JSON`);
    this.name = "DocumentEncodeError";
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

const documentDiagnostic = (
  diagnostic: Record<string, unknown>,
): DocumentInputDiagnostic => {
  const {reasonCode, pointer, modelPath, entityKind, nodeType, message, ...details} = diagnostic;
  return Object.freeze({
    reasonCode: String(reasonCode),
    ...(typeof pointer === "string" ? {pointer} : {}),
    ...(typeof modelPath === "string" ? {modelPath} : {}),
    ...(entityKind === "group" || entityKind === "field" ? {entityKind} : {}),
    ...(typeof nodeType === "string" ? {nodeType: nodeType as DocumentJsonNodeType} : {}),
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
    case "A12_DOCUMENT_NOT_REPRESENTABLE":
      return new DocumentEncodeError(
        String(details.modelPath),
        String(details.pointer),
        failure.message,
      );
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
