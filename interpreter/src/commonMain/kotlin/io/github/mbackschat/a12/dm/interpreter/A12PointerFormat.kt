package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot

/**
 * One part of an exact A12 pointer — the counterpart of A12's `PathPart`: a `name` and a `repetitionIndex`.
 *
 * <b>The domain is `PathPartImpl`'s, and it is looser than the pointer STRING grammar.</b> A non-empty name and
 * `repetitionIndex >= 0` ("repetition index must be > 0, or 0 for wildcard, but not negative") — and nothing
 * else. `PathPart.of("a/b", 1)` and `PathPart.of("Order$", 1)` are both accepted by the kernel and survive into
 * `DocumentPointer.fullName()`; only `DocumentPointer.of(String)` refuses those names, because a *written*
 * pointer has to parse back to the same value. This type previously imposed the string grammar here, which made
 * a legal A12 part unrepresentable — an over-rejection on the read path as much as the authoring one.
 *
 * Whether a `0` may sit at *this* position is the pointer's rule, not the part's, exactly as in A12:
 * `DocumentPointerImpl`'s constructor decides that, and its assertion records that "PathPart is responsible for
 * repetition indexes >= 0".
 *
 * A `data class` is safe here where it is not on [A12Pointer]: this holds no collection, so `copy()` re-runs the
 * primary constructor and re-validates rather than side-stepping a snapshot.
 */
data class A12PathPart(val name: String, val repetitionIndex: Int) {
    init {
        require(name.isNotEmpty()) { "PathPart name must not be empty" }
        require(repetitionIndex >= 0) {
            "repetition index must be >= 1, or ${A12PointerFormat.LAST_PART_ZERO} for the RepetitionsV2 slot, " +
                "but was $repetitionIndex — a negative index belongs to A12PartiallyKnownMultiPointer, not to " +
                "an exact pointer"
        }
    }

    /** `PathPartImpl.toString`: the index is written only when it is not the implicit 1. */
    override fun toString(): String = A12PointerSyntax.renderPart(name, repetitionIndex)
}

/**
 * One **exact** A12 `DocumentPointer`: an ordered list of [A12PathPart], with A12's derived views over it.
 *
 * <b>The shape is the owner's.</b> `DocumentPointer` holds `List<PathPart>` and *derives* `fullName()` and
 * `repetitionIndexes()` from it. This type used to be a pair of parallel `names`/`repetitions` lists, which is
 * a shape A12 does not have — the parallel lists appear only as *factory arguments* there
 * (`DocumentPointer.of(fullName, repetitions)`), never as the value's state.
 *
 * <b>Repetition domain</b>, measured against `DocumentPointerImpl`'s constructor: every index `>= 1`, except the
 * last, which may also be [A12PointerFormat.LAST_PART_ZERO] to address A12's `RepetitionsV2` slot. Nothing
 * negative is representable; a repetition that could not be resolved to a row belongs to
 * [A12PartiallyKnownMultiPointer].
 *
 * <b>Zero parts is legal.</b> That is A12's root pointer: `DocumentPointerImpl.parseFullName("")` returns an
 * empty part list and the class displays that value as `(empty DocumentPointer)`, a display name no illegal
 * value gets. Rejecting it would invent a shape stricter than A12's.
 *
 * Construction goes through [of], and never through a generated `copy()`: the parts list is snapshotted, so a
 * `data class` would be unable to validate-and-snapshot in one step (`init` cannot reassign a `val` constructor
 * property) and its `copy()` would hand back an unchecked value.
 */
class A12Pointer private constructor(parts: List<A12PathPart>) {

    /** The parts, in order — `DocumentPointer.getPathParts()`. Immutable and independent of the caller's list. */
    val pathParts: List<A12PathPart> = immutableListSnapshot(parts)

    /** `DocumentPointer.size()`. */
    val size: Int get() = pathParts.size

    /** `DocumentPointer.fullName()` — the names with a leading slash each, so the root pointer renders as `""`. */
    val fullName: String = pathParts.joinToString(separator = "") { "/${it.name}" }

    /** `DocumentPointer.repetitionIndexes()`. Immutable, like [pathParts], rather than a fresh mutable copy. */
    val repetitionIndexes: List<Int> = immutableListSnapshot(pathParts.map(A12PathPart::repetitionIndex))

    /** `DocumentPointer.getPartAtLevel(level)`. */
    fun partAtLevel(level: Int): A12PathPart = pathParts[level]

    override fun equals(other: Any?): Boolean = other is A12Pointer && other.pathParts == pathParts

    override fun hashCode(): Int = pathParts.hashCode()

    /** `DocumentPointerImpl.toString`, including its display name for the root pointer. */
    override fun toString(): String =
        if (pathParts.isEmpty()) "(empty A12Pointer)" else pathParts.joinToString("/")

    companion object {
        /**
         * The one construction path, and the counterpart of `DocumentPointer.of(List<PathPart>)`: it enforces
         * that factory's rule and only that one — the **position** rule over already-valid parts. It does not
         * impose the string codec's name grammar, because A12's own part-list factory does not
         * (`DocumentPointer.of(List.of(PathPart.of("a/b", 1)))` is accepted and yields `fullName` `/a/b`).
         * Snapshots, so no caller-owned list stays attached.
         */
        fun of(pathParts: List<A12PathPart>): A12Pointer {
            A12PointerFormat.requireExactDomain(
                pathParts.map(A12PathPart::repetitionIndex),
            ) { "pointer '${pathParts.joinToString("/")}'" }
            return A12Pointer(pathParts)
        }
    }
}

/**
 * A12's canonical **exact** `DocumentPointer` string — the project's single addressing form for a resolved cell
 * (CLAUDE.md "NEVER invent a shape A12 already defines"). Grammar, per kernel 30.8.1
 * `DocumentPointerImpl`/`PathPartImpl`: parts joined by `/`, each part a `name` optionally followed by `[n]`.
 * An absent bracket means repetition 1 and the canonical form *omits* `[1]`, so `Recipe/Ingredients[3]/Name` is
 * canonical and a field part carries no bracket. A leading `/` is accepted and stripped as redundant; a trailing
 * `/` is rejected.
 *
 * <b>This is the EXACT pointer only, and the distinction is load-bearing.</b> Two sibling A12 types share the
 * bracket syntax and mean different things by it:
 *
 *  - `DocumentMultiPointer`, where `0` is `WILDCARD` ("all repetitions") and legal at **every** part — the
 *    kernel's own example is `a[0]/b[2]`, wildcard on the first.
 *  - `PartiallyKnownDocumentMultiPointer`, the type `IMessage` returns, which admits that same wildcard *and*
 *    `UNKNOWN` (`-5`) at any part. That one is modelled by [A12PartiallyKnownMultiPointer].
 *
 * On an exact pointer `0` is neither: on the last part it addresses the `RepetitionsV2` node, and earlier it is
 * simply rejected. This file once called `0` "the wildcard" while enforcing the exact rule, and later admitted
 * `-5` as well — borrowing one type's vocabulary, then its domain, for another type's constraint. The union of
 * the two domains is a shape A12 has nowhere, so it belonged to neither channel; both are now separate types
 * whose accept/reject sets are measured against the kernel factories in `A12PointerKernelDomainDiffTest`.
 */
object A12PointerFormat {

    /**
     * The repetition A12 permits only on an exact pointer's LAST part, where it addresses `RepetitionsV2`.
     *
     * Deliberately NOT named `WILDCARD`: that is `DocumentMultiPointer`'s meaning for the same digit, and
     * conflating the two is what made this type's constraint look like a bug.
     */
    const val LAST_PART_ZERO: Int = 0

    /**
     * Render [path] (with or without a leading `/`) and its positional [repetitions] as the canonical string.
     *
     * <b>This is the string CODEC, so it carries the codec's precondition</b> — every written name must match
     * the part grammar, so that what it emits [parse] reads back as the same pointer. That is narrower than
     * [A12Pointer]'s value domain on purpose, and the narrowing belongs to the string, not to the pointer: a
     * part whose name carries a `/` or a bracket is a legal [A12PathPart] and simply cannot be *written*.
     *
     * It used to check only the arity, which let it emit 4,321 strings its own parser refused.
     */
    fun format(path: String, repetitions: List<Int>): String {
        val names = A12PointerSyntax.splitNames(path)
        A12PointerSyntax.requireCodecArgs(names, repetitions) { "path '$path'" }
        requireExactDomain(repetitions) { "path '$path'" }
        return names.asSequence()
            .mapIndexed { i, name -> A12PointerSyntax.renderPart(name, repetitions[i]) }
            .joinToString("/")
    }

    /** The canonical string of an already-built [pointer] — no re-parse of its [A12Pointer.fullName]. */
    fun render(pointer: A12Pointer): String = pointer.pathParts.joinToString("/")

    /** Parse a canonical (or leading-slash) exact pointer string. */
    fun parse(pointer: String): A12Pointer =
        A12Pointer.of(A12PointerSyntax.parts(pointer).map { A12PathPart(it.name, it.repetition) })

    /**
     * <b>The ONE place the exact pointer's POSITION rule lives</b>, holding the domain measured on
     * `DocumentPointerImpl`'s constructor. [A12Pointer.of] and [format] both call it, and [parse] reaches it
     * through [A12Pointer.of], so no entry point can admit a position another refuses. The defect this replaces
     * was that the rule lived inside `parse`'s loop and bound `parse`'s callers only.
     *
     * Deliberately NOT extended to the name grammar: that is the string codec's, and A12's part-list factory
     * does not apply it.
     */
    internal fun requireExactDomain(repetitions: List<Int>, subject: () -> String) {
        for (i in repetitions.indices) {
            val repetition = repetitions[i]
            val lastPartZero = repetition == LAST_PART_ZERO && i == repetitions.size - 1
            require(repetition >= 1 || lastPartZero) {
                "all repetition indexes must be >= 1 and only the last may be $LAST_PART_ZERO, in " +
                    "${subject()} — this is an exact DocumentPointer. A wildcard at an earlier part, or A12's " +
                    "unknown repetition (${A12PartiallyKnownMultiPointer.UNKNOWN}), is a " +
                    "PartiallyKnownDocumentMultiPointer and has its own type"
            }
        }
    }
}
