import type {
  InterpreterBridgeModelMetadata,
  InterpreterBridgePointer,
  InterpreterBridgeRelevantPointer,
} from "../internal/a12-interpreter-core.mjs";

import {
  DocumentCoordinateError,
  throwBridgeFailure,
  type DocumentCoordinateReasonCode,
} from "./errors.js";

/** Whether a model-owned pointer addresses a group row or field cell. */
export type ModelEntityKind = "group" | "field";
/** One exact repetition or every repetition at one partial-validation level. */
export type RelevantCoordinate = number | "ALL";

const pointerCores =
  new WeakMap<DocumentPointer, InterpreterBridgePointer>();
const relevantCores =
  new WeakMap<RelevantSelectionPointer, InterpreterBridgeRelevantPointer>();

/** Exact model-owned address of one declared group row or field cell. */
export abstract class DocumentPointer {
  readonly entityKind: ModelEntityKind;
  readonly path: string;
  readonly coordinates: readonly number[];

  /** @internal */
  protected constructor(core: InterpreterBridgePointer) {
    this.entityKind = core.entityKind as ModelEntityKind;
    this.path = core.path;
    this.coordinates = Object.freeze([...core.coordinates]);
    pointerCores.set(this, core);
  }

  /** @internal */
  bridgeValue(): InterpreterBridgePointer {
    const core = pointerCores.get(this);
    if (core === undefined) {
      throw new TypeError("pointer was not created by this interpreter package");
    }
    return core;
  }
}

/** Exact model-owned group-row pointer. */
export class GroupPointer extends DocumentPointer {
  private constructor(core: InterpreterBridgePointer) {
    super(core);
  }

  /** @internal */
  static fromBridge(core: InterpreterBridgePointer): GroupPointer {
    return new GroupPointer(core);
  }
}

/** Exact model-owned field-cell pointer. */
export class FieldPointer extends DocumentPointer {
  private constructor(core: InterpreterBridgePointer) {
    super(core);
  }

  /** @internal */
  static fromBridge(core: InterpreterBridgePointer): FieldPointer {
    return new FieldPointer(core);
  }
}

/** Partial-validation pointer containing at least one all-repetitions selector. */
export class RelevantSelectionPointer {
  readonly entityKind: ModelEntityKind;
  readonly path: string;
  readonly coordinates: readonly RelevantCoordinate[];

  private constructor(
    core: InterpreterBridgeRelevantPointer,
    coordinates: readonly RelevantCoordinate[],
  ) {
    this.entityKind = core.entityKind as ModelEntityKind;
    this.path = core.path;
    this.coordinates = Object.freeze([...coordinates]);
    relevantCores.set(this, core);
  }

  /** @internal */
  static fromBridge(
    core: InterpreterBridgeRelevantPointer,
    coordinates: readonly RelevantCoordinate[],
  ): RelevantSelectionPointer {
    return new RelevantSelectionPointer(core, coordinates);
  }

  /** @internal */
  bridgeValue(): InterpreterBridgeRelevantPointer {
    const core = relevantCores.get(this);
    if (core === undefined) {
      throw new TypeError("pointer was not created by this interpreter package");
    }
    return core;
  }
}

/** Exact or all-repetitions model-owned pointer accepted by partial validation. */
export type RelevantEntityPointer = DocumentPointer | RelevantSelectionPointer;

/** @internal */
export const relevantBridgeValue = (
  pointer: RelevantEntityPointer,
): InterpreterBridgeRelevantPointer =>
  pointer instanceof DocumentPointer
    ? pointer.bridgeValue().asRelevant()
    : pointer.bridgeValue();

/** @internal */
export const pointerFromBridge = (
  core: InterpreterBridgePointer,
): DocumentPointer =>
  core.entityKind === "field"
    ? FieldPointer.fromBridge(core)
    : GroupPointer.fromBridge(core);

/** @internal */
export const relevantPointerFromBridge = (
  core: InterpreterBridgeRelevantPointer,
): RelevantEntityPointer =>
  RelevantSelectionPointer.fromBridge(
    core,
    Object.freeze(core.coordinates.map((coordinate) =>
      coordinate === 0 ? "ALL" : coordinate)),
  );

const coordinateFailure = (
  reason: DocumentCoordinateReasonCode,
  path: string,
  coordinateIndex: number,
  message: string,
): never => {
  throw new DocumentCoordinateError(
    reason,
    path,
    coordinateIndex,
    undefined,
    undefined,
    message,
  );
};

const exactCoordinates = (
  path: string,
  coordinates: readonly number[],
): number[] =>
  coordinates.map((coordinate, index) => exactCoordinate(path, coordinate, index));

const exactCoordinate = (
  path: string,
  coordinate: number,
  coordinateIndex: number,
): number => {
    if (!Number.isSafeInteger(coordinate) || coordinate < 1) {
      return coordinateFailure(
        "coordinate-not-positive",
        path,
        coordinateIndex,
        `coordinate ${coordinateIndex} of '${path}' must be a positive safe integer`,
      );
    }
    if (coordinate > 2_147_483_647) {
      return coordinateFailure(
        "coordinate-outside-portable-range",
        path,
        coordinateIndex,
        `coordinate ${coordinateIndex} of '${path}' exceeds the portable 32-bit range`,
      );
    }
    return coordinate;
};

/** Prepared-model metadata and checked pointer factories. */
export class ModelMetadata {
  /** Exact DocumentModel id from the prepared entry model. */
  readonly documentModelId: string;

  /** @internal */
  constructor(private readonly core: InterpreterBridgeModelMetadata) {
    this.documentModelId = core.documentModelId;
  }

  /** Create an exact group-row or field-cell pointer after path and coordinate checks. */
  pointer(path: string, coordinates: readonly number[]): DocumentPointer {
    const result = this.core.pointer(path, exactCoordinates(path, coordinates));
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no pointer");
    }
    return pointerFromBridge(result.value);
  }

  /** Create an exact or all-repetitions pointer for partial validation. */
  relevantPointer(
    path: string,
    coordinates: readonly RelevantCoordinate[],
  ): RelevantEntityPointer {
    if (coordinates.every((coordinate) => coordinate !== "ALL")) {
      return this.pointer(path, coordinates as readonly number[]);
    }
    const bridgeCoordinates = coordinates.map((coordinate, index) =>
      coordinate === "ALL"
        ? 0
        : exactCoordinate(path, coordinate, index));
    const result = this.core.relevantPointer(path, bridgeCoordinates);
    if (result.failure !== null && result.failure !== undefined) {
      throwBridgeFailure(result.failure);
    }
    if (result.value === null || result.value === undefined) {
      throw new Error("interpreter bridge returned no relevance pointer");
    }
    return RelevantSelectionPointer.fromBridge(result.value, coordinates);
  }
}
