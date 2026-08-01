import {bridge} from "./internal/bridge.js";

/** One part of an exact A12 pointer — A12's `PathPart`: a name and its 1-based repetition index. */
export interface A12PathPart {
  readonly name: string;
  readonly repetitionIndex: number;
}

/**
 * One **exact** A12 `DocumentPointer`: its ordered parts, plus the two views A12 derives from them.
 *
 * Every `repetitionIndex` is `>= 1`, except the last, which may also be
 * {@link A12PointerFormat.LAST_PART_ZERO} to address A12's `RepetitionsV2` slot. Nothing negative is
 * representable here — a repetition the engine could not resolve to a row is an
 * {@link A12PartiallyKnownMultiPointer}, which is a different type.
 */
export interface A12Pointer {
  readonly pathParts: readonly A12PathPart[];
  /** `DocumentPointer.fullName()` — each name with a leading slash, so the root pointer is `""`. */
  readonly fullName: string;
  readonly repetitionIndexes: readonly number[];
}

/**
 * A12's `PartiallyKnownDocumentMultiPointer` — the addressing type the **message channel** uses.
 *
 * This is the counterpart of `Message.errorPath`, `referenced` and `fillToFix`, because A12's own
 * `IMessage.getErrorFieldPointer()`/`getReferencedFieldsPointers()`/`getRefOmissionErrorResponsiblePointers()`
 * return exactly this type. Its indexes admit {@link A12PartiallyKnownMultiPointerFormat.WILDCARD} at any part
 * and {@link A12PartiallyKnownMultiPointerFormat.UNKNOWN} for a level that matched no row.
 */
export interface A12PartiallyKnownMultiPointer {
  /** `fullName()` — each name with a leading slash, so the root pointer is `"/"`. */
  readonly fullName: string;
  readonly repetitionIndexes: readonly number[];
}

const rejected = (message: string | null | undefined): TypeError =>
  new TypeError(
    message === null || message === undefined || message === ""
      ? "the interpreter rejected the pointer without stating why"
      : message,
  );

const formatted = (
  result: {
    readonly value: string | null | undefined;
    readonly rejection: string | null | undefined;
  },
): string => {
  if (result.value === null || result.value === undefined) {
    throw rejected(result.rejection);
  }
  return result.value;
};

const frozenPointer = (parsed: {
  readonly pathParts: ReadonlyArray<{readonly name: string; readonly repetitionIndex: number}>;
  readonly fullName: string;
  readonly repetitionIndexes: readonly number[];
}): A12Pointer =>
  Object.freeze({
    pathParts: Object.freeze(
      parsed.pathParts.map((part) =>
        Object.freeze({name: part.name, repetitionIndex: part.repetitionIndex}),
      ),
    ),
    fullName: parsed.fullName,
    repetitionIndexes: Object.freeze([...parsed.repetitionIndexes]),
  });

/**
 * A12's canonical **exact** `DocumentPointer` string — parts joined by `/`, each part optionally followed by
 * `[n]`, with an absent bracket meaning repetition 1: `Order/Items[2]/Count`.
 *
 * This is deliberately **not** the spelling the reports carry. `ValidationFinding.errorPath`, `referenced`, and
 * `fillToFix` use the interpreter's house form — a leading `/` and an explicit `[n]` on every non-terminal part,
 * including `[1]`: `/Order[1]/Items[2]/Count` — and they may carry a repetition this type cannot express at all.
 * Those belong to {@link A12PartiallyKnownMultiPointerFormat}; this one reads a resolved cell address.
 *
 * A malformed argument raises a `TypeError`. That is a caller contract violation, not a model diagnostic, so it
 * carries no code and no structured details and is not one of this package's named `Error` subclasses.
 */
export class A12PointerFormat {
  /** The repetition A12 permits only on an exact pointer's last part, where it addresses `RepetitionsV2`. */
  static readonly LAST_PART_ZERO: number = bridge.interpreterBridgeA12LastPartZero();

  private constructor() {
  }

  /** Render `path` (with or without a leading `/`) and its positional `repetitions` as the canonical string. */
  static format(path: string, repetitions: readonly number[]): string {
    return formatted(bridge.interpreterBridgeFormatA12Pointer(path, [...repetitions]));
  }

  /** Parse a canonical, house-form, or leading-slash exact pointer. */
  static parse(pointer: string): A12Pointer {
    const result = bridge.interpreterBridgeParseA12Pointer(pointer);
    const parsed = result.value;
    if (parsed === null || parsed === undefined) {
      throw rejected(result.rejection);
    }
    return frozenPointer(parsed);
  }
}

/**
 * Reads and writes {@link A12PartiallyKnownMultiPointer}, and carries A12's two sentinels for it.
 *
 * Use this for anything that came out of the **message channel**: it accepts every repetition a report path can
 * carry, including the unknown index a parallel iteration leaves behind, which {@link A12PointerFormat} refuses
 * because A12's exact pointer cannot express it.
 */
export class A12PartiallyKnownMultiPointerFormat {
  /** `DocumentMultiPointer.WILDCARD` — all repetitions of a level. Legal at **every** part on this type. */
  static readonly WILDCARD: number = bridge.interpreterBridgeA12PartiallyKnownWildcard();

  /**
   * `PartiallyKnownDocumentMultiPointer.UNKNOWN` (`IIdentifier.UNKNOWN`) — not an index and not a wildcard, but
   * a level the engine could not resolve to a row, which a parallel iteration can leave at any joined level.
   */
  static readonly UNKNOWN: number = bridge.interpreterBridgeA12PartiallyKnownUnknown();

  private constructor() {
  }

  /** Render `path` and its positional `repetitions`, omitting `[1]` exactly as A12 renders a part. */
  static format(path: string, repetitions: readonly number[]): string {
    return formatted(
      bridge.interpreterBridgeFormatA12PartiallyKnownPointer(path, [...repetitions]),
    );
  }

  /** Parse a canonical, house-form, or leading-slash message pointer. */
  static parse(pointer: string): A12PartiallyKnownMultiPointer {
    const result = bridge.interpreterBridgeParseA12PartiallyKnownPointer(pointer);
    const parsed = result.value;
    if (parsed === null || parsed === undefined) {
      throw rejected(result.rejection);
    }
    return Object.freeze({
      fullName: parsed.fullName,
      repetitionIndexes: Object.freeze([...parsed.repetitionIndexes]),
    });
  }

  /**
   * `toDocumentPointer()` — the exact pointer when this message pointer is one, else `null`.
   *
   * `null` is a legitimate answer, not a failure: a wildcard above the last part, or an unknown index anywhere,
   * names a set or an unresolved level rather than one cell. A malformed argument still raises a `TypeError`.
   */
  static toExactPointer(pointer: A12PartiallyKnownMultiPointer): A12Pointer | null {
    const result = bridge.interpreterBridgeA12PartiallyKnownPointerToExact(
      pointer.fullName,
      [...pointer.repetitionIndexes],
    );
    if (result.rejection !== null && result.rejection !== undefined) {
      throw rejected(result.rejection);
    }
    const parsed = result.value;
    return parsed === null || parsed === undefined ? null : frozenPointer(parsed);
  }
}
