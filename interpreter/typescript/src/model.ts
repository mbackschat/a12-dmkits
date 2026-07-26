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

export type ModelEntityKind = "group" | "field";
export type RelevantCoordinate = number | "ALL";

const pointerCores =
  new WeakMap<DocumentPointer, InterpreterBridgePointer>();
const relevantCores =
  new WeakMap<RelevantSelectionPointer, InterpreterBridgeRelevantPointer>();

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

export class GroupPointer extends DocumentPointer {
  private constructor(core: InterpreterBridgePointer) {
    super(core);
  }

  /** @internal */
  static fromBridge(core: InterpreterBridgePointer): GroupPointer {
    return new GroupPointer(core);
  }
}

export class FieldPointer extends DocumentPointer {
  private constructor(core: InterpreterBridgePointer) {
    super(core);
  }

  /** @internal */
  static fromBridge(core: InterpreterBridgePointer): FieldPointer {
    return new FieldPointer(core);
  }
}

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

export class ModelMetadata {
  readonly documentModelId: string;

  /** @internal */
  constructor(private readonly core: InterpreterBridgeModelMetadata) {
    this.documentModelId = core.documentModelId;
  }

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
