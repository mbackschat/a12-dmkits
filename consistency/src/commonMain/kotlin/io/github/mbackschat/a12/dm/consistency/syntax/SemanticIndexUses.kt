package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * One `[value For key]` semantic index, read as the PAIR its laws are about.
 *
 * [semanticIndexKeysIn] answers what stands in the key position and nothing else, which is all a law about
 * the key's KIND needs. Every other law of that construct compares the two halves — the key's type against
 * the indexed group's index field, the key's location against the indexed group — or asks what the key
 * CARRIES, so it needs them together and it needs the modifiers the reference scan deliberately steps over.
 *
 * @param valuePath the indexed reference, normalized and with any repetition wildcard kept; absent when the
 *     run before the keyword named nothing this reader could reduce.
 * @param keyPath the entity key, same reduction — absent for a literal key.
 * @param keyLiteral the string key with its quotes stripped and A12's doubled `""` collapsed — absent for
 *     an entity key. Exactly one of the two is present on a well-formed index.
 * @param keyCategory the name behind a `->` on the key, which is a value CATEGORY rather than an entity.
 * @param nested whether the INDEXED half is itself a semantic index, which is what `[A For B For C]` is:
 *     A12's grammar is left-recursive there, so the outer index's value spec is the inner index.
 */
internal data class SemanticIndexUse(
    val valuePath: String?,
    val keyPath: String?,
    val keyLiteral: String?,
    val keyCategory: String?,
    val nested: Boolean,
)

/**
 * Every semantic index the text writes, in authored order.
 *
 * [keyword] arrives already resolved for the model's condition language, since `For` and `Fuer` are
 * different words and only the catalog knows which one this model speaks. A QUOTED `'For'` is a different
 * token kind and so is correctly not a use of the construct — that is A12's own escape for a field whose
 * name collides with a keyword.
 */
internal fun semanticIndexUsesIn(
    text: String,
    language: ConditionLanguage,
    keyword: String,
): List<SemanticIndexUse> {
    val tokens = tokenize(text)
    val keywords = CONDITION_KEYWORDS.getValue(language)
    val found = mutableListOf<RawUse>()
    tokens.indices.forEach { at ->
        if (tokens[at].kind != TokenKind.NAME || tokens[at].text != keyword) {
            return@forEach
        }
        val value = tokens.pathEndingBefore(at, keywords)
        val key = tokens.keyStartingAfter(at, keywords)
        found += RawUse(value, key)
    }
    return found.map { use ->
        SemanticIndexUse(
            use.value.path,
            use.key.path,
            use.key.literal,
            use.key.category,
            nested = found.any { other -> other !== use && other.key.begin == use.value.begin },
        )
    }
}

/** A use before the nesting question is answered, which needs every use's token spans. */
private class RawUse(val value: PathRun, val key: KeyRun)

/** Where a path run sits and what it reduces to; [begin] is -1 when it named nothing. */
private class PathRun(val path: String?, val begin: Int)

/** The key half: exactly one of [path] and [literal], plus what the key carries. */
private class KeyRun(val path: String?, val literal: String?, val category: String?, val begin: Int)

/**
 * The path run ENDING immediately before [at] — the indexed half, which stands to the left of the keyword.
 *
 * Walked backwards because that is where it sits, and stopped by anything a path cannot contain: a delimiter,
 * an operator, or a NAME the language reads as a keyword.
 */
private fun List<Token>.pathEndingBefore(at: Int, keywords: Map<String, KeywordKind>): PathRun {
    var begin = at
    while (begin > 0 && this[begin - 1].isPathToken(keywords)) {
        begin--
    }
    var end = at
    while (end > begin && this[end - 1].text in PATH_TAIL_SEPARATORS) {
        end--
    }
    val run = subList(begin, end)
    return if (run.any { it.isName() }) PathRun(normalizedPath(run), begin) else PathRun(null, -1)
}

/** The key run starting immediately after [at], with the modifiers a reference scan steps over. */
private fun List<Token>.keyStartingAfter(at: Int, keywords: Map<String, KeywordKind>): KeyRun {
    val first = getOrNull(at + 1) ?: return KeyRun(null, null, null, -1)
    if (first.kind == TokenKind.STRING) {
        val literal = first.text.removeSurrounding("\"").replace("\"\"", "\"")
        return KeyRun(null, literal, categoryAt(at + 2), at + 1)
    }
    var end = at + 1
    while (end < size && this[end].isPathToken(keywords)) {
        end++
    }
    while (end > at + 1 && this[end - 1].text in PATH_TAIL_SEPARATORS) {
        end--
    }
    val run = subList(at + 1, end)
    if (!run.any { it.isName() }) {
        return KeyRun(null, null, null, -1)
    }
    return KeyRun(normalizedPath(run), null, categoryAt(end), at + 1)
}

/** The category name behind a `->` at [at], or null when there is no arrow there. */
private fun List<Token>.categoryAt(at: Int): String? {
    if (getOrNull(at)?.text != "->") {
        return null
    }
    return getOrNull(at + 1)?.takeIf { it.isName() }?.text
}

private fun Token.isPathToken(keywords: Map<String, KeywordKind>): Boolean = when {
    kind == TokenKind.NAME && text in keywords -> false
    isName() -> true
    kind == TokenKind.NUMBER -> true
    else -> text in PATH_PUNCTUATION
}

private val PATH_TAIL_SEPARATORS = setOf("/", ".")
