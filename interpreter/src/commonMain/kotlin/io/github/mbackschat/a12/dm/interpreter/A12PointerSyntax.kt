package io.github.mbackschat.a12.dm.interpreter

/** One part as the shared grammar reads it, before any domain rule is applied. */
internal class A12RawPointerPart(val name: String, val repetition: Int)

/**
 * The pointer-**string** grammar, single-sourced — and nothing else. It belongs to the string codec, not to any
 * pointer value.
 *
 * <b>A12 gives its entry points DIFFERENT domains, and this file exists to keep that visible.</b> Measured
 * against kernel 30.8.1's own factories:
 *
 * | entry point | what it validates |
 * |---|---|
 * | `PathPartImpl(name, index)` | a **non-empty** name and `index >= 0` — `PathPart.of("a/b", 1)` is accepted |
 * | `DocumentPointerImpl(List<PathPart>)` | the **position** rule only: non-last `>= 1`, last may be `0` |
 * | `DocumentPointerImpl.of(String)` | *this* grammar — `([\w_-]+)(\[(\d+)?\])?` per part |
 * | `PartiallyKnownDocumentMultiPointerImpl(fullName, reps)` | splitting and normalization; **no** name rule |
 *
 * So `[NAME_PATTERN]` is the precondition of reading and writing a pointer **string**, so that what a codec
 * emits its own parser can read back. It is *not* a statement about which names A12 permits in a pointer value,
 * and applying it to a value factory is an over-rejection — one this file used to commit at four entry points
 * (`A12PointerKernelDomainDiffTest` decides each against its own owner now).
 *
 * A12 layers the same way — its string parser applies a pattern first and a numeric rule afterwards — which is why the two live apart here too.
 */
internal object A12PointerSyntax {

    /** A12's part-name grammar — the first group of `DocumentPointerImpl.pathPartPattern`, a STRING rule. */
    const val NAME_PATTERN: String = "[\\w_-]+"

    private val NAME = Regex(NAME_PATTERN)

    // The closing bracket MUST stay escaped: Kotlin/JS compiles a Regex in unicode (`u`) mode, where a lone
    // `]` is a SyntaxError — the pattern would fail to construct and every use would see an uninitialized
    // object. Harmless on the JVM, required on JS (the multiplatform `:interpreter:check` is what catches it).
    // The sign is admitted by the grammar and refused by each caller's own domain rule, so a message can say
    // WHY rather than "not a valid path part".
    private val PART = Regex("($NAME_PATTERN)(\\[(-?\\d+)?\\])?")

    /**
     * The names of a path, with the leading slash treated as decoration.
     *
     * Behaviour, stated as the contract rather than as an implementation: one leading `/` is optional and
     * carries no meaning; an otherwise-empty path names **no** parts, which is the document root, so `""` and a
     * bare `"/"` are the same pointer; and a path that ends in `/` after that stripping is malformed, because
     * the empty tail would be a part with no name.
     */
    fun splitNames(path: String): List<String> {
        val body = path.removePrefix("/")
        if (body.isEmpty()) {
            return emptyList()
        }
        require(!body.endsWith("/")) { "path must not end with a slash but was '$path'" }
        return body.split("/")
    }

    /** Read every part of [pointer]; an absent bracket means repetition 1, as it does on every A12 type. */
    fun parts(pointer: String): List<A12RawPointerPart> = splitNames(pointer).map { part ->
        val match = PART.matchEntire(part)
            ?: throw IllegalArgumentException("not a valid path part: '$part' in pointer '$pointer'")
        A12RawPointerPart(
            match.groupValues[1],
            match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 1,
        )
    }

    /** Both string forms render a part the way `PathPartImpl.toString` does: `[1]` is implicit and omitted. */
    fun renderPart(name: String, repetition: Int): String =
        if (repetition == 1) name else "$name[$repetition]"

    /** One index per name — the only rule shared by a codec and a value factory. */
    fun requireArity(names: List<String>, repetitions: List<Int>, subject: () -> String) {
        require(names.size == repetitions.size) {
            "${subject()} has ${names.size} parts but ${repetitions.size} repetitions"
        }
    }

    /**
     * <b>The string codec's precondition, and deliberately narrower than any A12 value domain.</b> A codec must
     * emit only what it can read back, so the names it writes have to be inside the part grammar — a name
     * carrying its own bracket or a `/` would round-trip to a different pointer. This says nothing about
     * `PathPart` or `DocumentPointer` value validity, both of which admit such names.
     */
    fun requireCodecArgs(names: List<String>, repetitions: List<Int>, subject: () -> String) {
        requireArity(names, repetitions, subject)
        names.forEach { name ->
            require(NAME.matchEntire(name) != null) {
                "not a valid path part name for the pointer STRING codec: '$name' in ${subject()} — a written " +
                    "name matches $NAME_PATTERN and carries no bracket of its own, so an already-indexed name " +
                    "like 'Group[2]' is passed as the name 'Group' with repetition 2 instead. A12's PathPart " +
                    "value itself is looser (any non-empty name); only what is WRITTEN into a pointer string " +
                    "is restricted, so that the string parses back to the same pointer"
            }
        }
    }
}
