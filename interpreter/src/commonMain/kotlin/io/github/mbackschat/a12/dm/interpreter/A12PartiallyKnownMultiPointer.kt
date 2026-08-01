package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.workspace.immutableListSnapshot

/**
 * A12's `PartiallyKnownDocumentMultiPointer` — the addressing type the **message channel** uses.
 *
 * <b>This is not a looser pointer; it is the type A12 puts on messages.</b> `IMessage.getErrorFieldPointer()`,
 * `getReferencedFieldsPointers()` and `getRefOmissionErrorResponsiblePointers()` all return it, and
 * `ICustomCondition.check` receives the error entity as one. So it is the counterpart of this library's
 * `Message.errorPath`, `referenced` and `fillToFix` — not of a resolved cell address.
 *
 * <b>Owner shape, member for member:</b> `fullName()` (the `/A/B/C` join, so the root pointer is `"/"`) plus
 * `repetitionIndexes()`. Unlike the exact pointer this genuinely *is* a name-string-plus-indexes value in A12 —
 * `PartiallyKnownDocumentMultiPointerImpl` stores `List<String> fullName` and `List<Integer> repetitions` and
 * exposes exactly those two accessors.
 *
 * <b>Domain</b>, from the same class's `checkValidity` and the interface's javadoc: an index is a concrete
 * repetition `>= 1`, or [A12PartiallyKnownMultiPointerFormat.WILDCARD] meaning all repetitions, or
 * [A12PartiallyKnownMultiPointerFormat.UNKNOWN] meaning no repetition carried the searched semantic index. The
 * wildcard is legal at **every** part here, which is the sharpest difference from the exact pointer.
 *
 * <b>Two deliberate strictnesses, recorded because being stricter than the kernel needs its reason written
 * down</b> (CLAUDE.md "A client-side refusal needs a measured kernel basis"):
 *
 *  1. **Arity is enforced.** `PartiallyKnownDocumentMultiPointerImpl`'s constructor does not compare the name
 *     count against the repetition count, yet `toDocumentPointer()` documents the omission away — "we do not
 *     need to check the length of both lists, as we only construct valid instances" — and would then throw
 *     inside a `try` whose `catch` asserts the throw is impossible. We enforce the invariant the owner's own
 *     comment claims.
 *  2. **A negative other than `-5` is rejected outright.** The kernel expresses that rule as a bare Java
 *     `assert`, so it is enforced under `-ea` (our test JVMs) and silently absent in production. Its own comment
 *     says the assertion exists so the javadoc can be adapted if the set ever widens; we hold to the documented
 *     set. `A12PointerKernelDomainDiffTest` measures both against the real factory.
 *
 * Construction goes through [A12PartiallyKnownMultiPointerFormat.of] or `parse`; the repetition list is
 * snapshotted, so no caller-owned list stays attached.
 */
class A12PartiallyKnownMultiPointer internal constructor(
    /** `fullName()` — every name with a leading slash, so a root pointer is `"/"` and never `""`. */
    val fullName: String,
    indexes: List<Int>,
) {

    /** `repetitionIndexes()`. Immutable and independent of the caller's list. */
    val repetitionIndexes: List<Int> = immutableListSnapshot(indexes)

    /**
     * `toDocumentPointer()` — the exact pointer when this value is one, else `null`.
     *
     * The owner's condition verbatim: every index before the last strictly greater than the wildcard, and the
     * last at least the wildcard. So a trailing `[0]` converts (it is the `RepetitionsV2` slot on an exact
     * pointer), while a wildcard further up or an `UNKNOWN` anywhere does not.
     */
    fun toExactPointer(): A12Pointer? {
        val names = A12PointerSyntax.splitNames(fullName)
        val convertible = repetitionIndexes.isEmpty() ||
            repetitionIndexes.dropLast(1).all { it > A12PartiallyKnownMultiPointerFormat.WILDCARD } &&
            repetitionIndexes.last() >= A12PartiallyKnownMultiPointerFormat.WILDCARD
        if (!convertible) {
            return null
        }
        return A12Pointer.of(names.mapIndexed { i, name -> A12PathPart(name, repetitionIndexes[i]) })
    }

    override fun equals(other: Any?): Boolean =
        other is A12PartiallyKnownMultiPointer &&
            other.fullName == fullName &&
            other.repetitionIndexes == repetitionIndexes

    override fun hashCode(): Int = 31 * fullName.hashCode() + repetitionIndexes.hashCode()

    /** `PartiallyKnownDocumentMultiPointerImpl.toString` — a debug form, never a pointer string. */
    override fun toString(): String = "A12PartiallyKnownMultiPointer[$fullName, $repetitionIndexes]"
}

/**
 * Reads and writes [A12PartiallyKnownMultiPointer] as a pointer string, and carries A12's two sentinels for it.
 *
 * <b>The string form is ours; the domain and the value shape are A12's.</b> A12 has no pointer-string rendering
 * for this type at all — `PartiallyKnownDocumentMultiPointerImpl.toString` is a debug form, and neither
 * `DocumentPointer.of(String)` nor `DocumentMultiPointer.of(String)` can read a `-5` (their part pattern is
 * `\d+`, unsigned). What A12 *does* have is a per-part renderer, `PathPartImpl.toString` — index omitted when it
 * is the implicit 1 — and an always-bracketed message form, `RtExternalIdentifier.toString`, which writes every
 * index including a negative. This applies the canonical per-part renderer to a partially-known value, so a
 * pointer with no wildcard and no unknown renders byte-identically to its exact counterpart, and one that cannot
 * be exact still round-trips. Inventing here is inside CLAUDE.md's exemption — the rendering is dmtool's own
 * published surface, not a domain object A12 owns — and the inventions the exact type had to give up were the
 * opposite case: a *domain* A12 defines.
 */
object A12PartiallyKnownMultiPointerFormat {

    /** `DocumentMultiPointer.WILDCARD` — all repetitions of this level. Legal at every part on this type. */
    const val WILDCARD: Int = 0

    /**
     * `PartiallyKnownDocumentMultiPointer.UNKNOWN` — `IIdentifier.UNKNOWN`, source-verified as `-5` in kernel
     * 30.8.1. Not an index and not a wildcard: it marks a level the engine could not resolve to a row, which a
     * parallel iteration produces when one joined group has no row carrying the current index value. The kernel
     * renders such a pointer as `…/Demand[-5]/Headcount`, so a consumer meets it in real message output.
     */
    const val UNKNOWN: Int = -5

    /** Build from the owner's own factory arguments: a full name and one index per name. */
    fun of(fullName: String, repetitionIndexes: List<Int>): A12PartiallyKnownMultiPointer {
        val names = A12PointerSyntax.splitNames(fullName)
        A12PointerSyntax.requireWellFormed(names, repetitionIndexes) { "pointer '$fullName'" }
        requirePartiallyKnownDomain(repetitionIndexes) { "pointer '$fullName'" }
        return A12PartiallyKnownMultiPointer(ownerFullName(names), repetitionIndexes)
    }

    /** Render [path] and its positional [repetitions] with `[1]` omitted, as A12 renders a part. */
    fun format(path: String, repetitions: List<Int>): String {
        val names = A12PointerSyntax.splitNames(path)
        A12PointerSyntax.requireWellFormed(names, repetitions) { "path '$path'" }
        requirePartiallyKnownDomain(repetitions) { "path '$path'" }
        return names.asSequence()
            .mapIndexed { i, name -> A12PointerSyntax.renderPart(name, repetitions[i]) }
            .joinToString("/")
    }

    /** Parse a canonical, always-bracketed, or leading-slash message pointer. */
    fun parse(pointer: String): A12PartiallyKnownMultiPointer {
        val parts = A12PointerSyntax.parts(pointer)
        val repetitions = parts.map(A12RawPointerPart::repetition)
        requirePartiallyKnownDomain(repetitions) { "pointer '$pointer'" }
        return A12PartiallyKnownMultiPointer(ownerFullName(parts.map(A12RawPointerPart::name)), repetitions)
    }

    /** The pointer string of an already-built value — no re-parse of its [A12PartiallyKnownMultiPointer.fullName]. */
    fun render(pointer: A12PartiallyKnownMultiPointer): String =
        A12PointerSyntax.splitNames(pointer.fullName).asSequence()
            .mapIndexed { i, name -> A12PointerSyntax.renderPart(name, pointer.repetitionIndexes[i]) }
            .joinToString("/")

    /** `PartiallyKnownDocumentMultiPointerImpl.fullName()`: `joining("/", "/", "")`, so the root is `"/"`. */
    private fun ownerFullName(names: List<String>): String = names.joinToString("/", prefix = "/")

    /**
     * <b>The ONE place this type's repetition domain is enforced</b> — [of], [format] and [parse] all call it,
     * so none can admit what another refuses.
     */
    private fun requirePartiallyKnownDomain(repetitions: List<Int>, subject: () -> String) {
        repetitions.forEach { repetition ->
            require(repetition >= WILDCARD || repetition == UNKNOWN) {
                "a repetition index is a concrete repetition >= 1, $WILDCARD (all repetitions) or $UNKNOWN " +
                    "(unknown), but was $repetition in ${subject()}"
            }
        }
    }
}
