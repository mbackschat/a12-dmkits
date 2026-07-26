export interface ModelPreparationLimits {
  readonly maxModelCount?: number;
  readonly maxModelBytes?: number;
  readonly maxTotalBytes?: number;
  readonly maxPreparedBytes?: number;
  readonly maxReferenceDepth?: number;
  readonly maxReferenceEdges?: number;
  readonly maxStructureDepth?: number;
  readonly maxPreparedElements?: number;
}

export interface DocumentDecodeLimits {
  readonly maxGroupPlacements?: number;
  readonly maxFieldPlacements?: number;
  readonly maxCoordinatesPerPlacement?: number;
  readonly maxPathCodeUnits?: number;
  readonly maxRawCodeUnitsPerField?: number;
  readonly maxTotalRawCodeUnits?: number;
  readonly maxModelNestingDepth?: number;
}

export interface DocumentDecodeOptions {
  readonly limits?: DocumentDecodeLimits;
}

const MAX_PORTABLE_INTEGER = 2_147_483_647;

export const requirePositivePortableInteger = (
  value: number,
  name: string,
): number => {
  if (!Number.isInteger(value) || value < 1 || value > MAX_PORTABLE_INTEGER) {
    throw new RangeError(
      `${name} must be a positive 32-bit integer (1..${MAX_PORTABLE_INTEGER})`,
    );
  }
  return value;
};

const normalizedOptions = (
  options: Readonly<Record<string, number | undefined>> | undefined,
): string | undefined => {
  if (options === undefined) {
    return undefined;
  }
  const normalized: Record<string, number> = {};
  for (const [name, value] of Object.entries(options)) {
    if (value !== undefined) {
      normalized[name] = requirePositivePortableInteger(value, name);
    }
  }
  return Object.keys(normalized).length === 0
    ? undefined
    : JSON.stringify(normalized);
};

export const preparationLimitsJson = (
  limits: ModelPreparationLimits | undefined,
): string | undefined =>
  normalizedOptions(limits as Readonly<Record<string, number | undefined>> | undefined);

export const documentLimitsJson = (
  limits: DocumentDecodeLimits | undefined,
): string | undefined =>
  normalizedOptions(limits as Readonly<Record<string, number | undefined>> | undefined);

export const documentLimitDefaults = {
  maxCoordinatesPerPlacement: 16,
  maxFieldPlacements: 100_000,
  maxGroupPlacements: 10_000,
  maxModelNestingDepth: 32,
  maxPathCodeUnits: 1_024,
  maxRawCodeUnitsPerField: 1_000_000,
  maxTotalRawCodeUnits: 16_000_000,
} as const satisfies Required<DocumentDecodeLimits>;

const documentLimitHardMaxima = {
  maxCoordinatesPerPlacement: 64,
  maxFieldPlacements: 1_000_000,
  maxGroupPlacements: 100_000,
  maxModelNestingDepth: 128,
  maxPathCodeUnits: 4_096,
  maxRawCodeUnitsPerField: 16_000_000,
  maxTotalRawCodeUnits: 64_000_000,
} as const satisfies Required<DocumentDecodeLimits>;

const effectiveLimit = <K extends keyof DocumentDecodeLimits>(
  name: K,
  supplied: number | undefined,
): number =>
  Math.min(
    supplied === undefined
      ? documentLimitDefaults[name]
      : requirePositivePortableInteger(supplied, name),
    documentLimitHardMaxima[name],
  );

export const effectiveDocumentLimits = (
  limits: DocumentDecodeLimits | undefined,
): Required<DocumentDecodeLimits> => ({
  maxCoordinatesPerPlacement: effectiveLimit(
    "maxCoordinatesPerPlacement",
    limits?.maxCoordinatesPerPlacement,
  ),
  maxFieldPlacements: effectiveLimit(
    "maxFieldPlacements",
    limits?.maxFieldPlacements,
  ),
  maxGroupPlacements: effectiveLimit(
    "maxGroupPlacements",
    limits?.maxGroupPlacements,
  ),
  maxModelNestingDepth: effectiveLimit(
    "maxModelNestingDepth",
    limits?.maxModelNestingDepth,
  ),
  maxPathCodeUnits: effectiveLimit("maxPathCodeUnits", limits?.maxPathCodeUnits),
  maxRawCodeUnitsPerField: effectiveLimit(
    "maxRawCodeUnitsPerField",
    limits?.maxRawCodeUnitsPerField,
  ),
  maxTotalRawCodeUnits: effectiveLimit(
    "maxTotalRawCodeUnits",
    limits?.maxTotalRawCodeUnits,
  ),
});
