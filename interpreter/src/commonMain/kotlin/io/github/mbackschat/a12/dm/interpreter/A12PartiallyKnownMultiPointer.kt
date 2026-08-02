package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot

/**
 * A12's `PartiallyKnownDocumentMultiPointer` — the addressing type the **message channel** uses.
 *
 * <b>This is not a looser pointer; it is the type A12 puts on messages.</b> `IMessage.getErrorFieldPointer()`,
 * `getReferencedFieldsPointers()` and `getRefOmissionErrorResponsiblePointers()` all return it, and
 * `ICustomCondition.check` receives the error entity as one.
 *
 * <b>The observable shape is the owner's:</b> `fullName()` (the `/A/B/C` join, so the root pointer is `"/"`) plus
 * `repetitionIndexes()`. Unlike the exact pointer this genuinely *is* a name-string-plus-indexes value in A12 —
 * `PartiallyKnownDocumentMultiPointerImpl` stores names and repetitions and exposes exactly those two accessors.
 *
 * <b>Domain</b>, from that class's `checkValidity` and the interface's javadoc: an index is a concrete
 * repetition `>= 1`, or [WILDCARD] meaning all repetitions, or [UNKNOWN] meaning no repetition carried the
 * searched semantic index. The wildcard is legal at **every** part here, which is the sharpest difference from
 * the exact pointer.
 *
 * <b>There is no pointer STRING for this type, and none is invented here.</b> A12 defines no parser and no
 * renderer for a single partially-known pointer — enumerated over the whole kernel checkout: the type is
 * mentioned in ten files, none of which parses or emits a pointer string for it; the impl's only `String`
 * producers are `fullName()` (names only, no indices) and a debug `toString`. So this type publishes the owner's
 * value and the owner's operations, and nothing else. A previous revision shipped a `format`/`parse`/`render`
 * trio for it; that was an invented serialization of an A12-owned domain object, which the dmtool
 * result-envelope exemption does not cover.
 *
 * <b>Two deliberate strictnesses, recorded because being stricter than the kernel needs its reason written
 * down</b> (CLAUDE.md "A client-side refusal needs a measured kernel basis"):
 *
 *  1. **Arity is enforced.** `PartiallyKnownDocumentMultiPointerImpl`'s constructor does not compare the name
 *     count against the repetition count, yet `toDocumentPointer()` documents the omission away — "we do not
 *     need to check the length of both lists, as we only construct valid instances" — and would then throw
 *     inside a `try` whose `catch` asserts the throw is impossible. We enforce the invariant that comment claims.
 *  2. **A negative other than `-5` is rejected outright.** The kernel expresses that rule as a bare Java
 *     `assert`, so it binds under `-ea` (our test JVMs) and is silently absent in production. Its own comment
 *     says the assertion exists so the javadoc can be adapted if the set ever widens; we hold to the documented
 *     set. `A12PointerKernelDomainDiffTest` measures both against the real factory.
 *
 * Every other admission decision is measured against the owner factory rather than derived — in particular the
 * `fullName` domain, which validates **splitting and normalization only**. `"/Order$"`, `"/Order Item"`,
 * `"/Order[2]"` and the interior-empty `"/Order//Items"` are all legal there, and imposing the exact string
 * parser's name grammar on this factory was an over-rejection. `A12PointerKernelDomainDiffTest` holds each
 * decision to its own kernel entry point.
 */
class A12PartiallyKnownMultiPointer private constructor(
    /** `fullName()` — every name with a leading slash, so a root pointer is `"/"` and never `""`. */
    val fullName: String,
    indexes: List<Int>,
) {

    /** `repetitionIndexes()`. Immutable and independent of the caller's list. */
    val repetitionIndexes: List<Int> = immutableListSnapshot(indexes)

    /**
     * The exact pointer this value names, when it names exactly one — A12's `toDocumentPointer()`.
     *
     * <b>Two things have to be true, and each has its own reason.</b>
     *
     *  1. **Every level resolves to one row.** The final level may additionally hold [WILDCARD], because on an
     *     exact pointer that digit is not a wildcard at all but the `RepetitionsV2` slot; above the last level a
     *     zero really is a wildcard. An unresolved level ([UNKNOWN]) or a wildcard above the last one names a set
     *     or a level that matched no row.
     *  2. **Every level can be named.** An exact pointer is a list of *parts*, and a part needs a non-empty name,
     *     so an empty level — the interior of `/Order//Items` — has no part to become. That name is legal on
     *     *this* type (the message factory validates splitting only), which is exactly why the case is reachable.
     *
     * Either way the answer is `null`, and `null` is an answer rather than a failure — A12 reports both as an
     * absent `Optional`. (For the empty level that is A12's PRODUCTION behaviour; its conversion catches the part
     * rejection and returns empty, guarded by a bare `assert` that additionally raises under `-ea`. We match what
     * runs in production, and `A12PointerKernelDomainDiffTest` measures both assertion modes so the difference is
     * recorded rather than absorbed.)
     *
     * <b>The root reports differently, and that is measured behaviour rather than a choice.</b> A12's own
     * conversion cannot answer for a zero-length pointer: it raises instead of returning an absent result
     * (`IllegalArgumentException`, kernel 30.8.1, in both assertion modes because the failure happens before its
     * own guard). Returning the root exact pointer here would be repairing an edge while claiming to be the
     * counterpart of the thing being repaired.
     */
    fun toExactPointer(): A12Pointer? {
        require(repetitionIndexes.isNotEmpty()) {
            "the root pointer has no exact counterpart to compute: A12's own conversion raises for a " +
                "zero-length pointer instead of reporting an absent result (measured, kernel 30.8.1)"
        }
        val finalLevel = repetitionIndexes.size - 1
        val namesOneCell = repetitionIndexes.withIndex().all { (level, repetition) ->
            if (level == finalLevel) repetition >= WILDCARD else repetition >= 1
        }
        if (!namesOneCell) {
            return null
        }
        val names = A12PointerSyntax.splitNames(fullName)
        if (names.any(String::isEmpty)) {
            return null
        }
        return A12Pointer.of(names.mapIndexed { level, name -> A12PathPart(name, repetitionIndexes[level]) })
    }

    override fun equals(other: Any?): Boolean =
        other is A12PartiallyKnownMultiPointer &&
            other.fullName == fullName &&
            other.repetitionIndexes == repetitionIndexes

    override fun hashCode(): Int = 31 * fullName.hashCode() + repetitionIndexes.hashCode()

    /** `PartiallyKnownDocumentMultiPointerImpl.toString` — a debug form, never a pointer string. */
    override fun toString(): String = "A12PartiallyKnownMultiPointer[$fullName, $repetitionIndexes]"

    companion object {
        /** `DocumentMultiPointer.WILDCARD` — all repetitions of this level. Legal at every part on this type. */
        const val WILDCARD: Int = 0

        /**
         * `PartiallyKnownDocumentMultiPointer.UNKNOWN` — `IIdentifier.UNKNOWN`, source-verified as `-5` in
         * kernel 30.8.1. Not an index and not a wildcard: it marks a level the engine could not resolve to a
         * row, which a parallel iteration produces when one joined group has no row carrying the current index
         * value.
         */
        const val UNKNOWN: Int = -5

        /**
         * <b>The one construction path</b>, and the counterpart of
         * `PartiallyKnownDocumentMultiPointer.of(String, List<Integer>)`. Validates, normalizes [fullName] the
         * way the owner's accessor does, and snapshots the index list.
         *
         * The primary constructor is private rather than `internal`: a Kotlin `internal constructor` on a public
         * class compiles to an `ACC_PUBLIC` JVM constructor with no synthetic flag and no name mangling, so an
         * ordinary Java consumer could call it and bypass every check here — measured, then closed.
         */
        fun of(fullName: String, repetitionIndexes: List<Int>): A12PartiallyKnownMultiPointer {
            val names = A12PointerSyntax.splitNames(fullName)
            A12PointerSyntax.requireArity(names, repetitionIndexes) { "pointer '$fullName'" }
            repetitionIndexes.forEach { repetition ->
                require(repetition >= WILDCARD || repetition == UNKNOWN) {
                    "a repetition index is a concrete repetition >= 1, $WILDCARD (all repetitions) or " +
                        "$UNKNOWN (unknown), but was $repetition in pointer '$fullName'"
                }
            }
            return A12PartiallyKnownMultiPointer(names.joinToString("/", prefix = "/"), repetitionIndexes)
        }
    }
}
