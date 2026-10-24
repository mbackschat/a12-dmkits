import {bridge} from "./internal/bridge.js";

/**
 * Compile-time brands that make the two pointer types **nominally** distinct.
 *
 * TypeScript is structurally typed, so without these an `A12Pointer` is assignable wherever an
 * `A12PartiallyKnownMultiPointer` is required — its members are a superset — and `toExactPointer` accepted an
 * exact pointer silently. Measured with `tsc 5.9.3 --strict`: only the reverse direction was rejected.
 *
 * The brands exist **only in the type system**. They are `declare`d unique symbols with no runtime value and no
 * emitted property, so nothing is added to the wire shape and the returned objects stay exactly the frozen
 * `{pathParts, fullName, repetitionIndexes}` / `{fullName, repetitionIndexes}` they were.
 */
declare const A12_EXACT_POINTER: unique symbol;
declare const A12_PARTIALLY_KNOWN_POINTER: unique symbol;

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
  readonly [A12_EXACT_POINTER]: true;
  readonly pathParts: readonly A12PathPart[];
  /** `DocumentPointer.fullName()` — each name with a leading slash, so the root pointer is `""`. */
  readonly fullName: string;
  readonly repetitionIndexes: readonly number[];
}

/**
 * A12's `PartiallyKnownDocumentMultiPointer` — the addressing type A12 puts on runtime **messages**, returned by
 * all three of `IMessage`'s address accessors and handed to `ICustomCondition`.
 *
 * Its indexes admit {@link A12PartiallyKnownMultiPointer.WILDCARD} at any part,
 * {@link A12PartiallyKnownMultiPointer.UNKNOWN} for a level that matched no row, and
 * {@link A12PartiallyKnownMultiPointer.PARALLEL_ITERATION}, which A12's runtime can leave on a parallel level.
 *
 * **There is no pointer string for this type.** A12 defines neither a parser nor a renderer for one, so this
 * package publishes the owner's value and the owner's operations and nothing else.
 */
export interface A12PartiallyKnownMultiPointer {
  readonly [A12_PARTIALLY_KNOWN_POINTER]: true;
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

const frozenExact = (parsed: {
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
  }) as unknown as A12Pointer;

/**
 * The one crossing of a message pointer into this package — used by the pointer factory below **and** by every
 * validation finding, so a finding's address is the same branded, frozen value a caller can build by hand.
 */
export const frozenPartiallyKnown = (parsed: {
  readonly fullName: string;
  readonly repetitionIndexes: readonly number[];
}): A12PartiallyKnownMultiPointer =>
  Object.freeze({
    fullName: parsed.fullName,
    repetitionIndexes: Object.freeze([...parsed.repetitionIndexes]),
  }) as unknown as A12PartiallyKnownMultiPointer;

/**
 * A12's canonical **exact** `DocumentPointer` string — parts joined by `/`, each part optionally followed by
 * `[n]`, with an absent bracket meaning repetition 1: `Order/Items[2]/Count`.
 *
 * This is the **string codec**, and its name grammar (`[\w_-]+` per part) is the codec's precondition, not the
 * pointer value's: A12's own `PathPart` accepts any non-empty name, and only what is *written* into a pointer
 * string is restricted, so that the string parses back to the same pointer.
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
    return frozenExact(parsed);
  }
}

/**
 * The owner operations of {@link A12PartiallyKnownMultiPointer}, mirroring the Kotlin companion.
 *
 * Declared beside the interface of the same name, so `A12PartiallyKnownMultiPointer` is both the type and its
 * factory — the way `PartiallyKnownDocumentMultiPointer` is in A12, where the static factories live on the
 * interface itself.
 */
export const A12PartiallyKnownMultiPointer = Object.freeze({
  /** `DocumentMultiPointer.WILDCARD` — all repetitions of a level. Legal at **every** part on this type. */
  WILDCARD: bridge.interpreterBridgeA12PartiallyKnownWildcard() as number,

  /**
   * `PartiallyKnownDocumentMultiPointer.UNKNOWN` (`IIdentifier.UNKNOWN`) — not an index and not a wildcard, but
   * a level the engine could not resolve to a row, which a parallel iteration can leave at any joined level.
   */
  UNKNOWN: bridge.interpreterBridgeA12PartiallyKnownUnknown() as number,

  /**
   * `IIdentifier.PARALLEL_ITERATION` — a level repeated in parallel across joined groups. A12 says return values
   * should usually not contain it, yet it reaches the references of a rule reporting on a keyed group inside a
   * group joined on another key.
   */
  PARALLEL_ITERATION: bridge.interpreterBridgeA12PartiallyKnownParallelIteration() as number,

  /** `PartiallyKnownDocumentMultiPointer.of(fullName, repetitions)` — the one construction path. */
  of(fullName: string, repetitionIndexes: readonly number[]): A12PartiallyKnownMultiPointer {
    const result = bridge.interpreterBridgeA12PartiallyKnownOf(fullName, [...repetitionIndexes]);
    const built = result.value;
    if (built === null || built === undefined) {
      throw rejected(result.rejection);
    }
    return frozenPartiallyKnown(built);
  },

  /**
   * `toDocumentPointer()` — the exact pointer when this message pointer is one, else `null`.
   *
   * `null` is a legitimate answer, not a failure: a wildcard above the last part, or a negative index (unknown
   * or parallel iteration) anywhere, names a set or an unresolved level rather than one cell. The **root** raises instead of answering, which is
   * A12's own measured behaviour for that edge rather than a choice made here.
   */
  toExactPointer(pointer: A12PartiallyKnownMultiPointer): A12Pointer | null {
    const result = bridge.interpreterBridgeA12PartiallyKnownPointerToExact(
      pointer.fullName,
      [...pointer.repetitionIndexes],
    );
    if (result.rejection !== null && result.rejection !== undefined) {
      throw rejected(result.rejection);
    }
    const parsed = result.value;
    return parsed === null || parsed === undefined ? null : frozenExact(parsed);
  },
});
