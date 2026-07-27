import type {
  InterpreterBridgeDocumentSnapshot,
  InterpreterBridgeDocuments,
  InterpreterBridgeDocumentInput,
} from "../internal/a12-interpreter-core.mjs";

import {
  DocumentInputError,
  type DocumentInputDiagnostic,
  ModelOwnerMismatchError,
  resultOf,
  throwBridgeFailure,
  type A12Result,
} from "./errors.js";
import {bridge} from "./internal/bridge.js";
import {
  documentLimitsJson,
  effectiveDocumentLimits,
  type DocumentDecodeLimits,
  type DocumentDecodeOptions,
} from "./limits.js";

/** One explicit group row in version 1 Document JSON. */
export interface GroupPlacementInput {
  readonly path: string;
  readonly coordinates: readonly number[];
}

/** One present field cell and its unchanged raw text in version 1 Document JSON. */
export interface FieldPlacementInput {
  readonly path: string;
  readonly coordinates: readonly number[];
  readonly raw: string;
}

/** Lossless portable Document JSON accepted by every supported runtime. */
export interface DocumentInputV1 {
  readonly version: 1;
  readonly groups: readonly GroupPlacementInput[];
  readonly fields: readonly FieldPlacementInput[];
}

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

type ShapeRecord = Record<string, unknown>;
type InputPath = readonly (string | number)[];

const valueKind = (value: unknown): string => {
  if (value === null) {
    return "null";
  }
  if (Array.isArray(value)) {
    return "array";
  }
  return typeof value;
};

const diagnostic = (
  reasonCode: string,
  inputPath: InputPath,
  message: string,
  details?: Readonly<Record<string, unknown>>,
): DocumentInputDiagnostic =>
  Object.freeze({
    reasonCode,
    inputPath: Object.freeze([...inputPath]),
    message,
    ...(details === undefined ? {} : {details: Object.freeze({...details})}),
  });

const nativeTypeDiagnostic = (
  inputPath: InputPath,
  expected: readonly string[],
  received: unknown,
): DocumentInputDiagnostic =>
  diagnostic(
    "invalid-native-type",
    inputPath,
    `expected ${expected.join(" or ")} at ${JSON.stringify(inputPath)}`,
    {expected: Object.freeze([...expected]), received: valueKind(received)},
  );

const missingPropertyDiagnostic = (
  inputPath: InputPath,
  property: string,
): DocumentInputDiagnostic =>
  diagnostic(
    "missing-property",
    inputPath,
    `missing required property '${property}'`,
    {property},
  );

const recordValue = (
  value: unknown,
  inputPath: InputPath,
  diagnostics: DocumentInputDiagnostic[],
): ShapeRecord | undefined => {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    diagnostics.push(nativeTypeDiagnostic(inputPath, ["object"], value));
    return undefined;
  }
  return value as ShapeRecord;
};

const checkKeys = (
  value: ShapeRecord,
  inputPath: InputPath,
  required: readonly string[],
  allowed: readonly string[],
  diagnostics: DocumentInputDiagnostic[],
): void => {
  for (const property of required) {
    if (!Object.hasOwn(value, property)) {
      diagnostics.push(missingPropertyDiagnostic([...inputPath, property], property));
    }
  }
  for (const property of Object.keys(value)) {
    if (!allowed.includes(property)) {
      diagnostics.push(
        diagnostic(
          "unknown-property",
          [...inputPath, property],
          `unknown property '${property}'`,
          {property, allowedProperties: Object.freeze([...allowed])},
        ),
      );
    }
  }
};

const resourceError = (
  inputPath: InputPath,
  limit: string,
  maximum: number,
  actual: number,
): DocumentInputError =>
  new DocumentInputError([
    diagnostic(
      "resource-limit-exceeded",
      inputPath,
      `${limit} exceeded: maximum ${maximum}, actual ${actual}`,
      {limit, maximum, actual},
    ),
  ]);

const requireResource = (
  inputPath: InputPath,
  limit: string,
  maximum: number,
  actual: number,
): void => {
  if (actual > maximum) {
    throw resourceError(inputPath, limit, maximum, actual);
  }
};

const parseCoordinates = (
  value: unknown,
  inputPath: InputPath,
  maximum: number,
  diagnostics: DocumentInputDiagnostic[],
): number[] | undefined => {
  if (!Array.isArray(value)) {
    diagnostics.push(nativeTypeDiagnostic(inputPath, ["array"], value));
    return undefined;
  }
  requireResource(
    inputPath,
    "coordinates-per-placement",
    maximum,
    value.length,
  );
  const coordinates: number[] = [];
  value.forEach((coordinate, index) => {
    const coordinatePath = [...inputPath, index];
    if (typeof coordinate !== "number") {
      diagnostics.push(
        diagnostic(
          "invalid-coordinate",
          coordinatePath,
          "coordinate must be a number",
          {coordinateIndex: index, reason: "not-number"},
        ),
      );
      return;
    }
    if (!Number.isSafeInteger(coordinate)) {
      diagnostics.push(
        diagnostic(
          "invalid-coordinate",
          coordinatePath,
          "coordinate must be a safe integer",
          {coordinateIndex: index, reason: "not-safe-integer"},
        ),
      );
      return;
    }
    if (coordinate < 1) {
      diagnostics.push(
        diagnostic(
          "invalid-coordinate",
          coordinatePath,
          "coordinate must be positive",
          {coordinateIndex: index, reason: "not-positive"},
        ),
      );
      return;
    }
    if (coordinate > 2_147_483_647) {
      diagnostics.push(
        diagnostic(
          "invalid-coordinate",
          coordinatePath,
          "coordinate exceeds the portable 32-bit document range",
          {coordinateIndex: index, reason: "outside-portable-range"},
        ),
      );
      return;
    }
    coordinates.push(coordinate);
  });
  return coordinates.length === value.length ? coordinates : undefined;
};

const requirePathResources = (
  path: string,
  inputPath: InputPath,
  limits: Required<DocumentDecodeLimits>,
): void => {
  requireResource(inputPath, "path-code-units", limits.maxPathCodeUnits, path.length);
  const nestingDepth = path.split("/").filter(Boolean).length;
  requireResource(
    inputPath,
    "model-nesting-depth",
    limits.maxModelNestingDepth,
    nestingDepth,
  );
};

const parseGroups = (
  value: unknown,
  limits: Required<DocumentDecodeLimits>,
  diagnostics: DocumentInputDiagnostic[],
): InstanceType<typeof bridge.InterpreterBridgeGroupPlacement>[] | undefined => {
  if (!Array.isArray(value)) {
    diagnostics.push(nativeTypeDiagnostic(["groups"], ["array"], value));
    return undefined;
  }
  requireResource(
    ["groups"],
    "group-placements",
    limits.maxGroupPlacements,
    value.length,
  );
  const groups: InstanceType<typeof bridge.InterpreterBridgeGroupPlacement>[] = [];
  value.forEach((item, index) => {
    const inputPath: InputPath = ["groups", index];
    const record = recordValue(item, inputPath, diagnostics);
    if (record === undefined) {
      return;
    }
    checkKeys(record, inputPath, ["path", "coordinates"], ["path", "coordinates"], diagnostics);
    const path = record.path;
    if (typeof path !== "string") {
      diagnostics.push(nativeTypeDiagnostic([...inputPath, "path"], ["string"], path));
    } else {
      requirePathResources(path, [...inputPath, "path"], limits);
    }
    const coordinates = parseCoordinates(
      record.coordinates,
      [...inputPath, "coordinates"],
      limits.maxCoordinatesPerPlacement,
      diagnostics,
    );
    if (typeof path === "string" && coordinates !== undefined) {
      groups.push(new bridge.InterpreterBridgeGroupPlacement(path, coordinates));
    }
  });
  return groups.length === value.length ? groups : undefined;
};

const parseFields = (
  value: unknown,
  limits: Required<DocumentDecodeLimits>,
  diagnostics: DocumentInputDiagnostic[],
): InstanceType<typeof bridge.InterpreterBridgeFieldPlacement>[] | undefined => {
  if (!Array.isArray(value)) {
    diagnostics.push(nativeTypeDiagnostic(["fields"], ["array"], value));
    return undefined;
  }
  requireResource(
    ["fields"],
    "field-placements",
    limits.maxFieldPlacements,
    value.length,
  );
  const fields: InstanceType<typeof bridge.InterpreterBridgeFieldPlacement>[] = [];
  let totalRawCodeUnits = 0;
  value.forEach((item, index) => {
    const inputPath: InputPath = ["fields", index];
    const record = recordValue(item, inputPath, diagnostics);
    if (record === undefined) {
      return;
    }
    checkKeys(
      record,
      inputPath,
      ["path", "coordinates", "raw"],
      ["path", "coordinates", "raw"],
      diagnostics,
    );
    const path = record.path;
    if (typeof path !== "string") {
      diagnostics.push(nativeTypeDiagnostic([...inputPath, "path"], ["string"], path));
    } else {
      requirePathResources(path, [...inputPath, "path"], limits);
    }
    const raw = record.raw;
    if (typeof raw !== "string") {
      diagnostics.push(nativeTypeDiagnostic([...inputPath, "raw"], ["string"], raw));
    } else {
      requireResource(
        [...inputPath, "raw"],
        "raw-code-units-per-field",
        limits.maxRawCodeUnitsPerField,
        raw.length,
      );
      totalRawCodeUnits += raw.length;
      requireResource(
        ["fields"],
        "total-raw-code-units",
        limits.maxTotalRawCodeUnits,
        totalRawCodeUnits,
      );
    }
    const coordinates = parseCoordinates(
      record.coordinates,
      [...inputPath, "coordinates"],
      limits.maxCoordinatesPerPlacement,
      diagnostics,
    );
    if (
      typeof path === "string" &&
      typeof raw === "string" &&
      coordinates !== undefined
    ) {
      fields.push(new bridge.InterpreterBridgeFieldPlacement(path, coordinates, raw));
    }
  });
  return fields.length === value.length ? fields : undefined;
};

const decodeUnknownDocument = (
  input: unknown,
  options: DocumentDecodeOptions | undefined,
): InterpreterBridgeDocumentInput => {
  const diagnostics: DocumentInputDiagnostic[] = [];
  const record = recordValue(input, [], diagnostics);
  if (record === undefined) {
    throw new DocumentInputError(diagnostics);
  }
  checkKeys(
    record,
    [],
    ["version", "groups", "fields"],
    ["version", "groups", "fields"],
    diagnostics,
  );
  if (typeof record.version !== "number") {
    diagnostics.push(nativeTypeDiagnostic(["version"], ["number"], record.version));
  } else if (record.version !== 1) {
    diagnostics.push(
      diagnostic(
        "unsupported-version",
        ["version"],
        `unsupported document version ${record.version}`,
        {expected: 1, actual: record.version},
      ),
    );
  }
  const limits = effectiveDocumentLimits(options?.limits);
  const groups = parseGroups(record.groups, limits, diagnostics);
  const fields = parseFields(record.fields, limits, diagnostics);
  if (diagnostics.length > 0 || groups === undefined || fields === undefined) {
    throw new DocumentInputError(Object.freeze([...diagnostics]));
  }
  return new bridge.InterpreterBridgeDocumentInput(1, groups, fields);
};

const bridgeDocumentFromTyped = (
  input: DocumentInputV1,
): InterpreterBridgeDocumentInput => {
  const typedCoordinate = (
    coordinate: number,
    placementKind: "group" | "field",
    placementIndex: number,
    coordinateIndex: number,
  ): number => {
    const inputPath = [
      placementKind === "group" ? "groups" : "fields",
      placementIndex,
      "coordinates",
      coordinateIndex,
    ] as const;
    const diagnostics: DocumentInputDiagnostic[] = [];
    const parsed = parseCoordinates(
      [coordinate],
      inputPath.slice(0, -1),
      1,
      diagnostics,
    );
    if (parsed === undefined || diagnostics.length > 0) {
      throw new DocumentInputError(diagnostics);
    }
    return parsed[0] as number;
  };
  const groups = input.groups.map((group, groupIndex) =>
    new bridge.InterpreterBridgeGroupPlacement(
      group.path,
      group.coordinates.map((coordinate, coordinateIndex) =>
        typedCoordinate(
          coordinate,
          "group",
          groupIndex,
          coordinateIndex,
        )),
    ));
  const fields = input.fields.map((field, fieldIndex) =>
    new bridge.InterpreterBridgeFieldPlacement(
      field.path,
      field.coordinates.map((coordinate, coordinateIndex) =>
        typedCoordinate(
          coordinate,
          "field",
          fieldIndex,
          coordinateIndex,
        )),
      field.raw,
    ));
  return new bridge.InterpreterBridgeDocumentInput(input.version, groups, fields);
};

const documentInputFromBridge = (
  input: InterpreterBridgeDocumentInput,
): DocumentInputV1 =>
  Object.freeze({
    version: 1 as const,
    groups: Object.freeze(input.groups.map((group) =>
      Object.freeze({
        path: group.path,
        coordinates: Object.freeze([...group.coordinates]),
      }))),
    fields: Object.freeze(input.fields.map((field) =>
      Object.freeze({
        path: field.path,
        coordinates: Object.freeze([...field.coordinates]),
        raw: field.raw,
      }))),
  });

/** Model-aware Document decoding, typed reading, ownership checks, and lossless encoding. */
export class PreparedDocumentApi {
  /** @internal */
  constructor(private readonly core: InterpreterBridgeDocuments) {}

  isSnapshot(value: unknown): value is DocumentSnapshot {
    return value instanceof DocumentSnapshot &&
      this.core.isSnapshot(value.bridgeValue());
  }

  decodeDocument(
    input: unknown,
    options?: DocumentDecodeOptions,
  ): DocumentSnapshot {
    return this.readBridgeDocument(decodeUnknownDocument(input, options), options);
  }

  tryDecodeDocument(
    input: unknown,
    options?: DocumentDecodeOptions,
  ): A12Result<DocumentSnapshot, DocumentInputError> {
    return resultOf(
      () => this.decodeDocument(input, options),
      DocumentInputError,
    );
  }

  readDocument(
    input: DocumentInputV1,
    options?: DocumentDecodeOptions,
  ): DocumentSnapshot {
    return this.readBridgeDocument(bridgeDocumentFromTyped(input), options);
  }

  encodeDocument(snapshot: DocumentSnapshot): DocumentInputV1 {
    if (!this.isSnapshot(snapshot)) {
      throw new ModelOwnerMismatchError();
    }
    return documentInputFromBridge(
      this.core.encodeDocument(snapshot.bridgeValue()),
    );
  }

  private readBridgeDocument(
    input: InterpreterBridgeDocumentInput,
    options: DocumentDecodeOptions | undefined,
  ): DocumentSnapshot {
    const result = this.core.readDocument(
      input,
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
}
