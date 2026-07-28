/** Optional resource ceilings for source admission, graph assembly, and prepared output. */
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

/** Optional resource ceilings applied before a Document snapshot is constructed. */
export interface DocumentDecodeLimits {
  readonly maxDocumentCodeUnits?: number;
  readonly maxGroupPlacements?: number;
  readonly maxFieldPlacements?: number;
  readonly maxPathCodeUnits?: number;
  readonly maxRawCodeUnitsPerField?: number;
  readonly maxTotalRawCodeUnits?: number;
  readonly maxModelNestingDepth?: number;
}

/** Options for reading A12 Document JSON. */
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
