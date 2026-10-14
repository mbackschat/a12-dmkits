package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * One `[value For key]` semantic index, read as the PAIR its laws are about.
 *
 * Its laws compare the two halves — the key's type against the indexed group's index field, the key's
 * location against the indexed group — or ask what the key CARRIES, so they need them together and they need
 * the modifiers the reference scan deliberately steps over.
 *
 * @param valuePath the indexed reference, normalized and with any repetition wildcard kept; absent when the
 *     run before the keyword named nothing this reader could reduce.
 * @param keyPath the entity key, same reduction — absent for a literal key.
 * @param keyLiteral the string key decoded once at the authored-token boundary — absent for
 *     an entity key. Exactly one of the two is present on a well-formed index.
 * @param keyCategory the name behind a `->` on the key, which is a value CATEGORY rather than an entity.
 * @param nested whether this index sits inside another's key, which is what the second `For` of
 *     `[A For B For C]` is: A12's grammar nests to the RIGHT, so `B For C` is the key of the index on `A`,
 *     and the check of the index on `B` finds an index enclosing it (`CheckEntityUtils.isSemanticIndex`,
 *     which walks every parent up to the root). An index in the body of a filter an entity key owns sits
 *     inside that key's production too, measured (`adapter.laws.CheckerFilterBodyAgreementTest`).
 * @param keywordAt where the keyword begins. A class of the index is published from here to [end], so its span
 *     encloses its key's, which is how report assembly knows the key's own gates run first where the two end
 *     together.
 * @param start where the key begins, or where the keyword ends when no key follows it.
 * @param end where the key's production ends, which is where A12 raises every class of this index. The key
 *     is an `entitySpec`, and one that carries its own `For` takes the next index into itself, so every index
 *     of a chain ends where the chain does — and a `Having` written after an entity key is the KEY's filter,
 *     the optional clause being taken by the innermost spec that can take it (`semanticIndex` and
 *     `entitySpec` in the kernel's `GrammarOnly_ConditionParser.g4`). So a filtered key ends where its filter
 *     does: at the comma or closing delimiter that ends the filter's condition. A STRING key is no spec and
 *     takes no filter, so it ends with its literal and a `Having` after it is the indexed operand's.
 */
internal data class SemanticIndexUse(
    val valuePath: String?,
    val keyPath: String?,
    val keyLiteral: String?,
    val keyCategory: String?,
    val nested: Boolean,
    val keywordAt: Int,
    val start: Int,
    val end: Int,
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
    val filter = CONDITION_OPERATOR_KEYWORDS.getValue(language)[HAVING]
    val found = mutableListOf<RawUse>()
    tokens.indices.forEach { at ->
        if (tokens[at].kind != TokenKind.NAME || tokens[at].text != keyword) {
            return@forEach
        }
        val value = tokens.pathEndingBefore(at, keywords)
        val key = tokens.keyStartingAfter(at, keywords, filter)
        found += RawUse(value, key, tokens[at].start)
    }
    return found.map { use ->
        SemanticIndexUse(
            use.value.path,
            use.key.path,
            use.key.literal,
            use.key.category,
            // A key or value that names nothing has no position, and -1 must enclose nothing.
            nested = use.value.begin >= 0 && found.any { other ->
                other !== use && other.key.begin >= 0 && use.value.begin in other.key.begin until other.key.end
            },
            keywordAt = use.keywordAt,
            start = if (use.key.begin < 0) tokens[use.key.end - 1].end else tokens[use.key.begin].start,
            end = tokens[found.chainEndOf(use)].end,
        )
    }
}

/** A use before the nesting question is answered, which needs every use's token spans. */
private class RawUse(val value: PathRun, val key: KeyRun, val keywordAt: Int)

/** The last token of the chain [use]'s key opens: its own, or that of the index nested on it. */
private tailrec fun List<RawUse>.chainEndOf(use: RawUse): Int {
    val nested = firstOrNull { other -> other !== use && use.key.begin >= 0 && other.value.begin == use.key.begin }
        ?: return use.key.end - 1
    return chainEndOf(nested)
}

/** Where a path run sits and what it reduces to; [begin] is -1 when it named nothing. */
private class PathRun(val path: String?, val begin: Int)

/**
 * The key half: exactly one of [path] and [literal], plus what the key carries.
 *
 * [begin] is -1 when no key follows the keyword; [end] is exclusive and counts a category access, and it is
 * the token after the keyword when no key follows.
 */
private class KeyRun(val path: String?, val literal: String?, val category: String?, val begin: Int, val end: Int)

/**
 * The path run ENDING immediately before [at] — the indexed half, which stands to the left of the keyword.
 *
 * Walked backwards because that is where it sits, and stopped by anything a path cannot contain: a delimiter,
 * an operator, or a NAME the language reads as a keyword. `@From` and `@Von` count as keywords although the
 * tokenizer emits the `@` apart, as the reference scan reads them: walking through the name half read the
 * scope of `RepetitionNotUnique(Key @From Group For "k")` as `FromGroup`, which resolves to nothing, and the
 * index went unjudged (`adapter.laws.CheckerRepetitionNotUniquePairedGateAgreementTest`).
 */
private fun List<Token>.pathEndingBefore(at: Int, keywords: Map<String, KeywordKind>): PathRun {
    // A category the indexed operand carries stands between its path and the keyword (KF568).
    val pathEnd = if (at >= 2 && this[at - 2].text == "->" && this[at - 1].isName()) at - 2 else at
    var begin = pathEnd
    while (begin > 0 && this[begin - 1].isPathToken(keywords) && !completesAnAtKeyword(begin - 1, keywords)) {
        begin--
    }
    var end = pathEnd
    while (end > begin && this[end - 1].text in PATH_TAIL_SEPARATORS) {
        end--
    }
    val run = subList(begin, end)
    return if (run.any { it.isName() }) PathRun(normalizedPath(run), begin) else PathRun(null, -1)
}

/** The key run starting immediately after [at], with the modifiers a reference scan steps over. */
private fun List<Token>.keyStartingAfter(at: Int, keywords: Map<String, KeywordKind>, filter: String?): KeyRun {
    val first = getOrNull(at + 1) ?: return KeyRun(null, null, null, -1, at + 1)
    if (first.kind == TokenKind.STRING) {
        // A string key is no `entitySpec`, so an arrow behind it projects nothing: A12 meets it as an
        // unexpected token (KF568).
        return KeyRun(null, first.text.decodeAuthoredStringLiteral(), null, at + 1, at + 2)
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
        return KeyRun(null, null, null, -1, at + 1)
    }
    val category = categoryAt(end)
    val keyEnd = filterEndFrom(if (category == null) end else end + 2, filter)
    return KeyRun(normalizedPath(run), null, category, at + 1, keyEnd)
}

/**
 * Where a key ending before [at] ends once a `Having` standing at [at] is counted: the token after the last
 * one its condition takes, which a comma or an unmatched closing delimiter ends. [at] itself when no filter
 * stands there.
 */
private fun List<Token>.filterEndFrom(at: Int, filter: String?): Int {
    if (filter == null || getOrNull(at)?.let { it.kind == TokenKind.NAME && it.text == filter } != true) {
        return at
    }
    var depth = 0
    for (index in at + 1 until size) {
        val text = this[index].text
        when {
            this[index].kind != TokenKind.PUNCTUATION -> Unit
            text in OPENING -> depth++
            text in CLOSING -> if (depth == 0) return index else depth--
            text == "," && depth == 0 -> return index
        }
    }
    return size
}

private val OPENING = setOf("(", "[", "{")

private val CLOSING = setOf(")", "]", "}")

/** The category name behind a `->` at [at], or null when there is no arrow there. */
private fun List<Token>.categoryAt(at: Int): String? {
    if (getOrNull(at)?.text != "->") {
        return null
    }
    return getOrNull(at + 1)?.takeIf { it.isName() }?.text
}

/** Whether the NAME at [at] is the second half of an `@`-keyword such as `@From`, its `@` adjacent before it. */
private fun List<Token>.completesAnAtKeyword(at: Int, keywords: Map<String, KeywordKind>): Boolean {
    val before = getOrNull(at - 1) ?: return false
    return this[at].kind == TokenKind.NAME && before.text == "@" && before.end == this[at].start &&
        "@" + this[at].text in keywords
}

private fun Token.isPathToken(keywords: Map<String, KeywordKind>): Boolean = when {
    kind == TokenKind.NAME && text in keywords -> false
    isName() -> true
    kind == TokenKind.NUMBER -> true
    else -> text in PATH_PUNCTUATION
}

private val PATH_TAIL_SEPARATORS = setOf("/", ".")
