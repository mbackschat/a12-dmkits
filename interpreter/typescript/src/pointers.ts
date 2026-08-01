import {bridge} from "./internal/bridge.js";

/**
 * One parsed **exact** A12 `DocumentPointer`: the path part names and their repetitions, positionally paired.
 *
 * Repetition {@link A12PointerFormat.LAST_PART_ZERO} is legal only on the last part, where it addresses A12's
 * `RepetitionsV2` slot; {@link A12PointerFormat.UNKNOWN_REPETITION} may appear at any part.
 */
export interface A12Pointer {
  readonly names: readonly string[];
  readonly repetitions: readonly number[];
}

const rejected = (message: string | null | undefined): TypeError =>
  new TypeError(
    message === null || message === undefined || message === ""
      ? "the interpreter rejected the pointer without stating why"
      : message,
  );

/**
 * A12's canonical `DocumentPointer` string — parts joined by `/`, each part optionally followed by `[n]`, with
 * an absent bracket meaning repetition 1: `Order/Items[2]/Count`.
 *
 * This is deliberately **not** the spelling the reports carry. `ValidationFinding.errorPath`, `referenced`, and
 * `fillToFix` use the interpreter's house form — a leading `/` and an explicit `[n]` on every non-terminal
 * part, including `[1]`: `/Order[1]/Items[2]/Count`. Converting between the two is what this utility is for:
 * {@link parse} accepts either spelling, {@link format} emits the canonical one.
 *
 * A malformed argument raises a `TypeError`. That is a caller contract violation, not a model diagnostic, so it
 * carries no code and no structured details and is not one of this package's named `Error` subclasses.
 */
export class A12PointerFormat {
  /** The repetition A12 permits only on an exact pointer's last part, where it addresses `RepetitionsV2`. */
  static readonly LAST_PART_ZERO: number = bridge.interpreterBridgeA12LastPartZero();

  /**
   * A12's **unknown** repetition (`IIdentifier.UNKNOWN`) — not an index and not a wildcard, but a level the
   * engine could not resolve to a row. It is accepted at any part, because a parallel iteration can leave any
   * joined level unresolved.
   */
  static readonly UNKNOWN_REPETITION: number = bridge.interpreterBridgeA12UnknownRepetition();

  private constructor() {
  }

  /** Render `path` (with or without a leading `/`) and its positional `repetitions` as the canonical string. */
  static format(path: string, repetitions: readonly number[]): string {
    const result = bridge.interpreterBridgeFormatA12Pointer(path, [...repetitions]);
    if (result.value === null || result.value === undefined) {
      throw rejected(result.rejection);
    }
    return result.value;
  }

  /** Parse a canonical, house-form, or leading-slash pointer into its names and repetitions. */
  static parse(pointer: string): A12Pointer {
    const result = bridge.interpreterBridgeParseA12Pointer(pointer);
    const parsed = result.value;
    if (parsed === null || parsed === undefined) {
      throw rejected(result.rejection);
    }
    return Object.freeze({
      names: Object.freeze([...parsed.names]),
      repetitions: Object.freeze([...parsed.repetitions]),
    });
  }
}
