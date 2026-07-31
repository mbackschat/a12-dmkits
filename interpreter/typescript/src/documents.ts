import type {
  InterpreterBridgeDocumentSnapshot,
  InterpreterBridgeDocuments,
} from "../internal/a12-interpreter-core.mjs";

import {
  DocumentInputError,
  type DocumentInputDiagnostic,
  type DocumentInputPathSegment,
  ModelOwnerMismatchError,
  resultOf,
  throwBridgeFailure,
  type A12Result,
} from "./errors.js";
import {
  documentLimitsJson,
  type DocumentDecodeOptions,
} from "./limits.js";

/** One field cell in A12 Document JSON: `null` is a present cell with no value. */
export type A12DocumentField = string | number | boolean | null;

/**
 * One group instance in A12 Document JSON — the Kernel's own document shape: a nested object mirrors a
 * non-repeating group, an **array** carries the repetitions of a repeating group (the index *is* the
 * repetition, an unfilled row is `{}`), a missing key is an omission, and a field is its value.
 */
export interface A12DocumentGroup {
  readonly [name: string]:
    | A12DocumentField
    | A12DocumentGroup
    | readonly A12DocumentGroup[];
}

/** A whole document in A12's canonical Document JSON, root group names at the top level. */
export type A12Document = A12DocumentGroup;

const snapshotCores =
  new WeakMap<DocumentSnapshot, InterpreterBridgeDocumentSnapshot>();

/** Immutable, opaque Document state owned by one prepared model. */
export class DocumentSnapshot {
  private constructor(core: InterpreterBridgeDocumentSnapshot) {
    snapshotCores.set(this, core);
  }

  /** @internal */
  static fromBridge(core: InterpreterBridgeDocumentSnapshot): DocumentSnapshot {
    return new DocumentSnapshot(core);
  }

  /** @internal */
  bridgeValue(): InterpreterBridgeDocumentSnapshot {
    const core = snapshotCores.get(this);
    if (core === undefined) {
      throw new ModelOwnerMismatchError();
    }
    return core;
  }
}

const valueKind = (value: unknown): string => {
  if (value === null) {
    return "null";
  }
  if (Array.isArray(value)) {
    return "array";
  }
  return typeof value;
};

const unrepresentableDiagnostic = (
  inputPath: readonly DocumentInputPathSegment[],
  received: unknown,
): DocumentInputDiagnostic =>
  Object.freeze({
    reasonCode: "unrepresentable-native-value",
    inputPath: Object.freeze([...inputPath]),
    message: `JSON cannot carry ${valueKind(received)} at ${JSON.stringify(inputPath)}`,
    details: Object.freeze({received: valueKind(received)}),
  });

/**
 * Reject a supplied value that `JSON.stringify` would silently drop or mangle — `undefined`, a function, a
 * symbol, `NaN`/`Infinity`, `BigInt` — before it reaches the shared engine. Everything the shape actually
 * means (unknown keys, wrong node types, resource ceilings) is the engine's judgement, not this check's.
 */
const requireJsonRepresentable = (
  value: unknown,
  inputPath: readonly DocumentInputPathSegment[],
  diagnostics: DocumentInputDiagnostic[],
): void => {
  if (value === null || typeof value === "string" || typeof value === "boolean") {
    return;
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      diagnostics.push(unrepresentableDiagnostic(inputPath, value));
    }
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((element, index) => {
      requireJsonRepresentable(element, [...inputPath, index], diagnostics);
    });
    return;
  }
  if (typeof value === "object") {
    for (const [name, child] of Object.entries(value as Record<string, unknown>)) {
      requireJsonRepresentable(child, [...inputPath, name], diagnostics);
    }
    return;
  }
  diagnostics.push(unrepresentableDiagnostic(inputPath, value));
};

const documentJson = (document: A12Document | string): string => {
  if (typeof document === "string") {
    return document;
  }
  const diagnostics: DocumentInputDiagnostic[] = [];
  requireJsonRepresentable(document, [], diagnostics);
  if (diagnostics.length > 0) {
    throw new DocumentInputError(Object.freeze([...diagnostics]));
  }
  return JSON.stringify(document);
};

/**
 * Model-aware reading and lossless writing of **A12's canonical Document JSON** — the one document shape at
 * this boundary. Both directions run through the shared engine, so a JavaScript, JVM, or native caller sees
 * the same validation, the same diagnostics, and the same canonical output.
 */
export class PreparedDocumentApi {
  /** @internal */
  constructor(private readonly core: InterpreterBridgeDocuments) {}

  /**
   * Create a deterministic, best-effort model-derived Document candidate. It carries every declared group and
   * field and instantiates one row of every repeatable group. Local field formats and bounds guide editable
   * source values; computation-owned targets remain present-empty for the engine to fill. Whole-model rule
   * acceptance is not guaranteed.
   */
  seedDocument(): DocumentSnapshot {
    return DocumentSnapshot.fromBridge(this.core.seedDocument());
  }

  /** Test whether a value is a Document snapshot owned by this prepared model. */
  isSnapshot(value: unknown): value is DocumentSnapshot {
    return value instanceof DocumentSnapshot &&
      this.core.isSnapshot(value.bridgeValue());
  }

  /** Read A12 Document JSON — a plain object or its text — into an immutable Document snapshot. */
  readDocument(
    document: A12Document | string,
    options?: DocumentDecodeOptions,
  ): DocumentSnapshot {
    const result = this.core.readDocument(
      documentJson(document),
      documentLimitsJson(options?.limits),
    );
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no document");
    }
    return DocumentSnapshot.fromBridge(result.value);
  }

  /** Return reading success or the same typed input error without throwing. */
  tryReadDocument(
    document: A12Document | string,
    options?: DocumentDecodeOptions,
  ): A12Result<DocumentSnapshot, DocumentInputError> {
    return resultOf(
      () => this.readDocument(document, options),
      DocumentInputError,
    );
  }

  /** Write an owned snapshot back as canonical A12 Document JSON. */
  encodeDocument(snapshot: DocumentSnapshot): A12Document {
    return JSON.parse(this.encodeDocumentJson(snapshot)) as A12Document;
  }

  /** Write an owned snapshot back as canonical A12 Document JSON text. */
  encodeDocumentJson(snapshot: DocumentSnapshot): string {
    if (!this.isSnapshot(snapshot)) {
      throw new ModelOwnerMismatchError();
    }
    const result = this.core.encodeDocument(snapshot.bridgeValue());
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no document");
    }
    return result.value;
  }
}
