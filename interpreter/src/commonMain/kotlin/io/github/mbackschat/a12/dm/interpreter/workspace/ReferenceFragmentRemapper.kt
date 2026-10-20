package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage

/** Provider-local entity and field names used to bind a mounted fragment before it moves. */
internal class SourceModelSymbols private constructor(
    entityPaths: Set<String>,
    fieldsByShortName: Map<String, List<String>>,
) {
    val entityPaths: Set<String> = entityPaths.toSet()
    val fieldsByShortName: Map<String, List<String>> =
        fieldsByShortName.mapValues { (_, paths) -> immutableListSnapshot(paths) }

    companion object {
        fun fromPaths(
            groupPaths: Set<String>,
            fieldPaths: Set<String>,
        ): SourceModelSymbols {
            val entities = linkedSetOf<String>().apply {
                addAll(groupPaths)
                addAll(fieldPaths)
            }
            val fields = linkedMapOf<String, MutableList<String>>()
            fieldPaths.forEach { path ->
                fields.getOrPut(path.substringAfterLast('/')) { mutableListOf() }.add(path)
            }
            return SourceModelSymbols(entities, fields)
        }
    }
}

/**
 * The source namespace in which one mounted executable was authored. [declaringGroup] and [sourceSymbols] remain
 * provider-local; [prefixMapping] projects a bound provider path onto the occurrence's final output path.
 */
internal class ReferenceBinding(
    val prefixMapping: MountedPrefixMapping,
    val declaringGroup: String,
    val sourceSymbols: SourceModelSymbols,
    val shortNamesAllowed: Boolean,
    val conditionLanguage: ConditionLanguage,
)

internal class ReferenceRemapResult internal constructor(
    val text: String,
    residualReferences: List<String>,
) {
    val residualReferences: List<String> = immutableListSnapshot(residualReferences)
}

internal data class FragmentLanguageProfile(
    val referenceSeparators: Set<String>,
    val nonReferenceAtoms: Set<String>,
    val customCondition: String,
    val atFrom: String,
    val suppressWarning: String,
)

/**
 * Rewrites reference atoms without reformatting the surrounding DSL. Quoted strings and comments are opaque;
 * relative paths remain byte-identical because their declaring executable and targets move together. A parse/render
 * round trip would destroy authored whitespace, comments, quoting, and keyword case, so a conservative lexical pass
 * recognizes only grammar positions that can contain a reference.
 *
 * Materialization and preventive byte measurement deliberately share [FragmentScanner]. Keeping one traversal is
 * the invariant that makes the capped pre-allocation measurement equal the text later emitted.
 */
internal object ReferenceFragmentRemapper {

    private const val CUSTOM_CONDITION = "CustomCondition"
    private const val AT_FROM = "From"
    private const val SUPPRESS_WARNING = "SuppressWarning"
    private val REFERENCE_SEPARATORS = setOf(
        "And",
        "Or",
        "In",
        "For",
        "Having",
        "PatternMatched",
        "PatternViolated",
        "DiffersWithToleranceRange1",
        "DiffersWithToleranceRange2",
        "DiffersWithToleranceRange5",
        "DiffersWithToleranceRange10",
    )
    private val NON_REFERENCE_ATOMS = setOf(
        "True",
        "False",
        "Today",
        "Now",
        "BaseYear",
        "FirstDay",
        "LastDay",
        "RuleGroup",
    )
    private val ENGLISH_PROFILE = createLanguageProfile(ConditionLanguage.EN)
    private val GERMAN_PROFILE = createLanguageProfile(ConditionLanguage.DE)

    internal fun languageProfile(language: ConditionLanguage): FragmentLanguageProfile =
        when (language) {
            ConditionLanguage.EN -> ENGLISH_PROFILE
            ConditionLanguage.DE -> GERMAN_PROFILE
        }

    private fun createLanguageProfile(language: ConditionLanguage): FragmentLanguageProfile =
        FragmentLanguageProfile(
            referenceSeparators = REFERENCE_SEPARATORS
                .flatMapTo(linkedSetOf()) { language.authoredForms(it) },
            nonReferenceAtoms = NON_REFERENCE_ATOMS
                .flatMapTo(linkedSetOf()) { language.authoredForms(it) },
            customCondition = language.authoredForms(CUSTOM_CONDITION).single(),
            atFrom = language.authoredForms(AT_FROM).single(),
            suppressWarning = language.authoredForms(SUPPRESS_WARNING).single(),
        )

    fun remap(source: String, binding: ReferenceBinding): ReferenceRemapResult =
        FragmentScanner(
            source = source,
            binding = binding,
            output = MaterializingFragmentOutput(source.length),
            collectResiduals = true,
        ).remap()

    /**
     * Measure the remapped compact-JSON string before allocating it. The capped result is [stopAfter] + 1 when the
     * output would exceed the caller's remaining prepared-byte allowance.
     */
    fun measureQuotedBytes(
        source: String,
        binding: ReferenceBinding,
        stopAfter: Long,
    ): Long =
        FragmentScanner(
            source = source,
            binding = binding,
            output = MeasuringFragmentOutput(stopAfter),
            collectResiduals = false,
        ).measureQuotedBytes()
}

/**
 * A small state machine over the reference-bearing surface, not a second condition parser. [AtomContext] prevents
 * operator slashes and adjacent identifiers from being rewritten, while grammar punctuation and language-scoped
 * separators reopen a reference slot. Any absolute provider path that cannot be projected is retained for a
 * fail-closed preparation diagnostic rather than guessed.
 */
private class FragmentScanner(
    private val source: String,
    private val binding: ReferenceBinding,
    private val output: FragmentOutput,
    private val collectResiduals: Boolean,
) {
    private val languageProfile = ReferenceFragmentRemapper.languageProfile(binding.conditionLanguage)
    private val residualReferences = mutableListOf<String>()
    private var position = 0
    // EXPECTED means a path-looking token occupies an operand/reference slot; COMPLETE prevents arithmetic
    // division and adjacent identifiers from being mistaken for references until grammar punctuation resets it.
    private var atomContext = AtomContext.EXPECTED
    private var skipNextIdentifier = false
    private var changed = false

    fun remap(): ReferenceRemapResult {
        scan()
        return ReferenceRemapResult(
            text = if (changed) output.materializedText() else source,
            residualReferences = residualReferences,
        )
    }

    fun measureQuotedBytes(): Long {
        scan()
        return output.quotedBytes
    }

    private fun scan() {
        while (position < source.length && !output.exceeded) {
            when (val current = charAt(position)) {
                '"' -> {
                    copyDoubleQuoted()
                    atomContext = AtomContext.COMPLETE
                }
                ';' -> if (charAt(position + 1) == ';') {
                    copyComment()
                } else {
                    copyOperator()
                }
                ' ', '\t', '\n', '\r' -> copyWhitespace()
                '[', '(', '{', ',' -> {
                    output.append(current)
                    position++
                    atomContext = AtomContext.EXPECTED
                }
                ']', ')', '}' -> {
                    output.append(current)
                    position++
                    atomContext = AtomContext.COMPLETE
                }
                '@' -> atomContext = copyAtKeyword()
                '-' -> if (charAt(position + 1) == '>') {
                    output.append("->")
                    position += 2
                    skipNextIdentifier = true
                    atomContext = AtomContext.EXPECTED
                } else {
                    copyOperator()
                }
                '$' -> if (atomContext == AtomContext.EXPECTED) {
                    output.append(current)
                    position++
                    val path = readPath(position)
                    if (path != null) {
                        appendPath(path)
                        atomContext = AtomContext.COMPLETE
                    }
                } else {
                    copyOperator()
                }
                '/' -> if (atomContext == AtomContext.EXPECTED) {
                    val path = readPath(position)
                    if (path == null) {
                        copyOperator()
                    } else {
                        appendPath(path)
                        atomContext = AtomContext.COMPLETE
                    }
                } else {
                    copyOperator()
                }
                '.', '\'' -> {
                    val path = if (
                        atomContext == AtomContext.EXPECTED &&
                        (current == '\'' || charAt(position + 1) == '.')
                    ) {
                        readPath(position)
                    } else {
                        null
                    }
                    if (path == null) {
                        output.append(current)
                        position++
                    } else {
                        appendPath(path)
                        atomContext = AtomContext.COMPLETE
                    }
                }
                else -> when {
                    current.isIdentifierStart() -> copyIdentifierOrPath()
                    current.isDigit() -> {
                        copyNumber()
                        atomContext = AtomContext.COMPLETE
                    }
                    else -> copyOperator()
                }
            }
        }
    }

    private fun copyIdentifierOrPath() {
        val wordEnd = identifierEnd(position)
        val word = source.substring(position, wordEnd)
        val next = skipWhitespace(wordEnd)
        when {
            skipNextIdentifier -> {
                output.append(source, position, wordEnd)
                position = wordEnd
                skipNextIdentifier = false
                atomContext = AtomContext.COMPLETE
            }
            word == languageProfile.customCondition -> {
                output.append(word)
                position = wordEnd
                skipNextIdentifier = true
                atomContext = AtomContext.EXPECTED
            }
            word in languageProfile.referenceSeparators -> {
                output.append(word)
                position = wordEnd
                atomContext = AtomContext.EXPECTED
            }
            word in languageProfile.nonReferenceAtoms -> {
                output.append(word)
                position = wordEnd
                atomContext = AtomContext.COMPLETE
            }
            charAt(next) == '(' -> {
                output.append(source, position, wordEnd)
                position = wordEnd
                atomContext = AtomContext.EXPECTED
            }
            atomContext == AtomContext.EXPECTED -> {
                val path = readPath(position)
                if (path == null) {
                    output.append(source, position, wordEnd)
                    position = wordEnd
                } else {
                    appendPath(path)
                }
                atomContext = AtomContext.COMPLETE
            }
            else -> {
                output.append(source, position, wordEnd)
                position = wordEnd
                atomContext = AtomContext.COMPLETE
            }
        }
    }

    private fun appendPath(path: PathToken) {
        when {
            path.absolute -> appendAbsolutePath(path)
            path.bareShortName -> appendShortName(path)
            else -> output.append(source, path.start, path.end)
        }
        position = path.end
    }

    private fun appendAbsolutePath(path: PathToken) {
        val sourceRoot = "/${path.firstSegment}"
        val mappedPrefix = binding.prefixMapping.outputPathFor(sourceRoot)
        if (mappedPrefix == null) {
            output.append(source, path.start, path.end)
            if (collectResiduals) {
                residualReferences += source.substring(path.start, path.end)
            }
            return
        }
        output.append(preserveQuotedMovedSegment(mappedPrefix, path))
        output.append(source, path.firstSegmentEnd, path.end)
        changed = true
    }

    private fun appendShortName(path: PathToken) {
        val localPath = "${binding.declaringGroup}/${path.firstSegment}"
        val matches = binding.sourceSymbols.fieldsByShortName[path.firstSegment].orEmpty()
        // The kernel binds a declaring-group entity first. Its optional model-wide fallback is field-only and
        // succeeds only for one provider-local match; an ambiguity must remain unresolved rather than be guessed.
        if (
            localPath in binding.sourceSymbols.entityPaths ||
            !binding.shortNamesAllowed ||
            matches.size != 1
        ) {
            output.append(source, path.start, path.end)
            return
        }
        val mappedTarget = remapBoundTarget(matches.single(), path)
        if (mappedTarget == null) {
            output.append(source, path.start, path.end)
            return
        }
        output.append(mappedTarget)
        changed = true
    }

    private fun remapBoundTarget(target: String, sourceToken: PathToken): String? {
        val moved = binding.prefixMapping.outputPathFor(target) ?: return null
        return if (sourceToken.quotedFirst) {
            quoteLastSegment(
                moved,
                source.substring(sourceToken.firstSegmentStart, sourceToken.firstSegmentEnd),
            )
        } else {
            moved
        }
    }

    private fun preserveQuotedMovedSegment(mappedPrefix: String, sourceToken: PathToken): String {
        if (
            !sourceToken.quotedFirst ||
            !mappedPrefix.endsWith("/${sourceToken.firstSegment}")
        ) {
            return mappedPrefix
        }
        return quoteLastSegment(
            mappedPrefix,
            source.substring(sourceToken.firstSegmentStart, sourceToken.firstSegmentEnd),
        )
    }

    private fun quoteLastSegment(path: String, authoredSegment: String): String {
        val slash = path.lastIndexOf('/')
        return path.substring(0, slash + 1) + authoredSegment
    }

    private fun readPath(start: Int): PathToken? {
        var cursor = start
        var absolute = false
        if (charAt(cursor) == '/') {
            absolute = true
            cursor = skipWhitespace(cursor + 1)
        } else if (charAt(cursor) == '.' && charAt(cursor + 1) == '.') {
            do {
                cursor = skipWhitespace(cursor + 2)
                if (
                    charAt(cursor) == '/' &&
                    charAt(skipWhitespace(cursor + 1)) == '.'
                ) {
                    cursor = skipWhitespace(cursor + 1)
                } else {
                    break
                }
            } while (charAt(cursor) == '.' && charAt(cursor + 1) == '.')
            if (charAt(cursor) == '/') {
                cursor = skipWhitespace(cursor + 1)
            } else {
                val turningPointEnd = readSegment(cursor)?.end ?: cursor
                return PathToken.relative(start, turningPointEnd, cursor, turningPointEnd)
            }
        }

        val first = readSegment(cursor) ?: return null
        val firstStart = cursor
        cursor = consumeWildcard(first.end)
        val starred = cursor != first.end
        var multipleSegments = false
        while (true) {
            val slash = skipWhitespace(cursor)
            if (charAt(slash) != '/') {
                break
            }
            val segmentStart = skipWhitespace(slash + 1)
            val segment = readSegment(segmentStart) ?: break
            multipleSegments = true
            cursor = consumeWildcard(segment.end)
        }
        val startsUpward = charAt(start) == '.' && charAt(start + 1) == '.'
        return PathToken(
            start = start,
            end = cursor,
            absolute = absolute,
            firstSegment = first.value,
            firstSegmentStart = firstStart,
            firstSegmentEnd = first.end,
            quotedFirst = first.quoted,
            bareShortName = !absolute && !multipleSegments && !starred && !startsUpward,
        )
    }

    private fun consumeWildcard(start: Int): Int {
        val star = skipWhitespace(start)
        return if (charAt(star) == '*') star + 1 else start
    }

    private fun readSegment(start: Int): Segment? {
        if (charAt(start) == '\'') {
            val end = source.indexOf('\'', startIndex = start + 1)
            if (end < 0 || end == start + 1) {
                return null
            }
            return Segment(source.substring(start + 1, end), end + 1, quoted = true)
        }
        if (!charAt(start).isIdentifierStart()) {
            return null
        }
        val end = identifierEnd(start)
        return Segment(source.substring(start, end), end, quoted = false)
    }

    private fun copyDoubleQuoted() {
        val start = position++
        var escaped = false
        while (position < source.length) {
            val current = source[position++]
            if (current == '"' && !escaped) {
                break
            }
            escaped = current == '\\' && !escaped
            if (current != '\\') {
                escaped = false
            }
        }
        output.append(source, start, position)
    }

    private fun copyComment() {
        val start = position
        position += 2
        while (
            position < source.length &&
            source[position] != '\n' &&
            source[position] != '\r'
        ) {
            position++
        }
        output.append(source, start, position)
    }

    private fun copyWhitespace() {
        val end = skipWhitespace(position)
        output.append(source, position, end)
        position = end
    }

    private fun copyAtKeyword(): AtomContext {
        output.append('@')
        position++
        val whitespaceEnd = skipWhitespace(position)
        output.append(source, position, whitespaceEnd)
        position = whitespaceEnd
        if (!charAt(position).isIdentifierStart()) {
            return AtomContext.COMPLETE
        }
        val end = identifierEnd(position)
        val keyword = source.substring(position, end)
        output.append(source, position, end)
        position = end
        return when (keyword) {
            languageProfile.atFrom -> AtomContext.EXPECTED
            languageProfile.suppressWarning -> {
                copyWaivedWarning()
                AtomContext.EXPECTED
            }
            else -> AtomContext.COMPLETE
        }
    }

    /**
     * The scale-gate waiver's `(NAME)` names a warning, never a reference, so it is copied verbatim; the condition
     * proper starts after it, which reopens a reference slot (KERNEL-FINDINGS KF75).
     */
    private fun copyWaivedWarning() {
        val close = source.indexOf(')', position)
        val end = if (close < 0) source.length else close + 1
        output.append(source, position, end)
        position = end
    }

    private fun copyNumber() {
        val start = position
        while (charAt(position).isDigit() || charAt(position) == '.') {
            position++
        }
        output.append(source, start, position)
    }

    private fun copyOperator() {
        val current = source[position++]
        output.append(current)
        if (current in "+-*/^<>=!") {
            atomContext = AtomContext.EXPECTED
        }
    }

    private fun identifierEnd(start: Int): Int {
        var end = start
        while (charAt(end).isIdentifierPart()) {
            end++
        }
        return end
    }

    private fun skipWhitespace(start: Int): Int {
        var cursor = start
        while (charAt(cursor).isWhitespace()) {
            cursor++
        }
        return cursor
    }

    private fun charAt(index: Int): Char =
        if (index in source.indices) source[index] else END

    private companion object {
        const val END = '\u0000'
    }
}

private interface FragmentOutput {
    val exceeded: Boolean
    val quotedBytes: Long

    fun append(value: Char)
    fun append(value: String)
    fun append(value: String, start: Int, end: Int)
    fun materializedText(): String
}

private class MaterializingFragmentOutput(
    initialCapacity: Int,
) : FragmentOutput {
    private val builder = StringBuilder(initialCapacity)

    override val exceeded: Boolean get() = false
    override val quotedBytes: Long get() = error("materializing output does not measure bytes")

    override fun append(value: Char) {
        builder.append(value)
    }

    override fun append(value: String) {
        builder.append(value)
    }

    override fun append(value: String, start: Int, end: Int) {
        builder.append(value, start, end)
    }

    override fun materializedText(): String = builder.toString()
}

private class MeasuringFragmentOutput(
    stopAfter: Long,
) : FragmentOutput {
    private val counter = CappedQuotedJsonCounter(stopAfter)

    override val exceeded: Boolean get() = counter.exceeded
    override val quotedBytes: Long get() = counter.count

    override fun append(value: Char) {
        counter.add(value)
    }

    override fun append(value: String) {
        counter.add(value)
    }

    override fun append(value: String, start: Int, end: Int) {
        counter.add(value, start, end)
    }

    override fun materializedText(): String =
        error("measuring output does not materialize text")
}

private enum class AtomContext {
    EXPECTED,
    COMPLETE,
}

private data class PathToken(
    val start: Int,
    val end: Int,
    val absolute: Boolean,
    val firstSegment: String,
    val firstSegmentStart: Int,
    val firstSegmentEnd: Int,
    val quotedFirst: Boolean,
    val bareShortName: Boolean,
) {
    companion object {
        fun relative(
            start: Int,
            end: Int,
            firstSegmentStart: Int,
            firstSegmentEnd: Int,
        ): PathToken = PathToken(
            start = start,
            end = end,
            absolute = false,
            firstSegment = "",
            firstSegmentStart = firstSegmentStart,
            firstSegmentEnd = firstSegmentEnd,
            quotedFirst = false,
            bareShortName = false,
        )
    }
}

private data class Segment(
    val value: String,
    val end: Int,
    val quoted: Boolean,
)

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_'

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'
