package io.github.mbackschat.a12.dm.interpreter

/** One part as the shared grammar reads it, before either addressing domain's rule is applied. */
internal class A12RawPointerPart(val name: String, val repetition: Int)

/**
 * The pointer-string **syntax** A12's two addressing domains share, single-sourced so neither can drift.
 *
 * A12 layers pointer validity in two steps and so do we: a *grammar* that reads a part into a name and an
 * optional bracketed index, then a per-type *domain rule* over the index. The kernel does the same —
 * `DocumentPointerImpl.parsePathParts` matches `([\w_-]+)(\[(\d+)?\])?` and *then* keeps a `repetition < 0`
 * guard its own pattern can never reach, which is the layering made visible in its source.
 *
 * The one deliberate difference: the sign is **in** this grammar, where A12's exact pattern has none. The
 * message channel really does carry `[-5]` (`RtExternalIdentifier.toString` renders every index, negatives
 * included), so a shared reader must be able to see one; the exact domain then refuses it with a message that
 * says *why* rather than "not a valid path part". Accept/reject is unchanged either way, and
 * `A12PointerKernelDomainDiffTest` measures that against the kernel factories.
 */
internal object A12PointerSyntax {

    /** A12's part-name grammar — the first group of `DocumentPointerImpl.pathPartPattern`. */
    const val NAME_PATTERN: String = "[\\w_-]+"

    private val NAME = Regex(NAME_PATTERN)

    // The closing bracket MUST stay escaped: Kotlin/JS compiles a Regex in unicode (`u`) mode, where a lone
    // `]` is a SyntaxError — the pattern would fail to construct and every use would see an uninitialized
    // object. Harmless on the JVM, required on JS (the multiplatform `:interpreter:check` is what catches it).
    private val PART = Regex("($NAME_PATTERN)(\\[(-?\\d+)?\\])?")

    fun isName(name: String): Boolean = NAME.matchEntire(name) != null

    /**
     * Split a path on `/`, stripping the redundant leading slash and rejecting a trailing one —
     * `DocumentPointerImpl.parseFullName`, condition for condition.
     *
     * A bare `"/"` is the one string that ends in a slash and is still legal: the kernel excludes it from the
     * trailing-slash refusal by name and then strips the slash to reach the empty part list, i.e. the root
     * pointer. This used to reject it, which made `A12PartiallyKnownMultiPointer`'s own `fullName()` for the
     * root — `"/"`, since its owner joins with a `"/"` prefix — unreadable by the very function that produced it.
     */
    fun splitNames(path: String): List<String> {
        require(path == "/" || !path.endsWith("/")) { "path must not end with a slash but was '$path'" }
        val body = path.removePrefix("/")
        return if (body.isEmpty()) emptyList() else body.split("/")
    }

    /** Read every part of [pointer]; an absent bracket means repetition 1, as it does on both A12 types. */
    fun parts(pointer: String): List<A12RawPointerPart> = splitNames(pointer).map { part ->
        val match = PART.matchEntire(part)
            ?: throw IllegalArgumentException("not a valid path part: '$part' in pointer '$pointer'")
        A12RawPointerPart(
            match.groupValues[1],
            match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 1,
        )
    }

    /** Both types render a part the way `PathPartImpl.toString` does: `[1]` is implicit and omitted. */
    fun renderPart(name: String, repetition: Int): String =
        if (repetition == 1) name else "$name[$repetition]"

    /**
     * The arity + name half of every domain rule, which is identical on both types. Only the repetition rule
     * differs, so only that stays in the per-type file.
     */
    fun requireWellFormed(names: List<String>, repetitions: List<Int>, subject: () -> String) {
        require(names.size == repetitions.size) {
            "${subject()} has ${names.size} parts but ${repetitions.size} repetitions"
        }
        names.forEach { name ->
            require(isName(name)) {
                "not a valid path part name: '$name' in ${subject()} — a name matches $NAME_PATTERN and carries " +
                    "no bracket of its own, so an already-indexed name like 'Group[2]' is passed as the name " +
                    "'Group' with repetition 2 instead"
            }
        }
    }
}
