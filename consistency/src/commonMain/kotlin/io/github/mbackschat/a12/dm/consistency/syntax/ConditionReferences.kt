package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * The entities a condition or operation text names, in the order they first appear.
 *
 * Each result is the reference AS WRITTEN — `Code`, `../Warehouse/Sku`, `Lines*` then `Amount` — because only the
 * whole path binds: resolving `Sku` alone would find a different field, or none. Binding them to declarations
 * is the caller's job (`PreparedModel.resolve`); this answers only which names a check has to bind.
 *
 * A name is a reference unless the lexer would resolve it as a keyword, and that question is settled per
 * [language]: the Kernel looks a name up in one language's terminals and every miss becomes an ordinary name
 * (`AbstractMultiLanguageHandler.setTypeInLexer`). A quoted name is a reference whatever it spells, which is
 * what quoting it is for.
 *
 * A repeated name is reported once. It names one element and the caller resolves names, so a second entry
 * would only make the same call twice; a consumer that needs each occurrence's position reads the tokens.
 *
 * **Not answered here, deliberately.** A `$`-prefixed reference is reported without the `$`, which is right
 * for binding because the marker selects an outer REPETITION of the same declared element rather than a
 * different one. A bare digit sequence is read as a constant, never as a reference, though the grammar does
 * admit an all-digit name (`nameOhnePunkt: … | ZiffernFolge | …`) — an all-digit field referenced bare is
 * therefore missed. And nothing here decides whether the text parses; a malformed condition yields whatever
 * names it contains.
 */
fun referencesIn(text: String, language: ConditionLanguage): List<String> =
    ReferenceScan(tokenize(text), CONDITION_KEYWORDS.getValue(language)).references()

/** Path syntax that continues a reference: the separator, the two step forms, and the repetition wildcard. */
private val PATH_PUNCTUATION = setOf("/", "..", ".", "*")

/** What a reference may end with — a trailing separator is a malformed tail, never part of the name. */
private val PATH_SEPARATORS = setOf("/", ".")

private class ReferenceScan(private val tokens: List<Token>, private val keywords: Map<String, KeywordKind>) {

    private var at = 0
    private val found = LinkedHashSet<String>()

    fun references(): List<String> {
        while (at < tokens.size) {
            val keyword = keywordLength()
            when {
                keyword > 0 -> at += keyword
                tokens[at].text == "->" -> skipCategoryName()
                startsReference(at) -> readReference()
                else -> at++
            }
        }
        return found.toList()
    }

    /**
     * How many tokens the keyword at [at] spans, or zero when there is none.
     *
     * Two, for `@From` and `@Von`: the lexer matches those as single terminals while the tokenizer emits the
     * `@` separately, so reading only the name half would leave `From` looking like an undeclared field.
     */
    private fun keywordLength(): Int {
        val token = tokens[at]
        val next = tokens.getOrNull(at + 1)
        if (next != null && next.kind == TokenKind.NAME && next.start == token.end &&
            token.text + next.text in keywords
        ) {
            return 2
        }
        return if (token.kind == TokenKind.NAME && token.text in keywords) 1 else 0
    }

    /**
     * Step over `->` and the name behind it. That name is a value CATEGORY
     * (`entitySpec … ('->' categoryName=nameOhnePunkt)?`), so resolving it as an entity would report every
     * categorized reference as undeclared.
     */
    private fun skipCategoryName() {
        at++
        if (at < tokens.size && tokens[at].isName()) at++
    }

    private fun startsReference(index: Int): Boolean {
        val token = tokens[index]
        return token.isName() || token.text == ".." || token.text == "/"
    }

    /**
     * Read the maximal run of path tokens that touch each other.
     *
     * Adjacency is the whole discriminator, and it is why the tokenizer records a span: the lexer's own
     * `entity` rule admits no whitespace between a path's parts, so `[A] / [B]` is a division and `A/B` is a
     * path, and the two are indistinguishable by token kind alone.
     */
    private fun readReference() {
        val begin = at
        var end = at
        var names = 0
        while (end < tokens.size && (end == begin || tokens[end].start == tokens[end - 1].end)) {
            val token = tokens[end]
            when {
                // A keyword ends the run: unquoted, the Kernel lexes it as the keyword, so it is not a segment.
                token.isName() && token.kind == TokenKind.NAME && token.text in keywords -> break
                token.isName() -> names++
                token.kind == TokenKind.NUMBER || token.text in PATH_PUNCTUATION -> Unit
                else -> break
            }
            end++
        }
        while (end > begin && tokens[end - 1].text in PATH_SEPARATORS) end--
        if (names > 0) {
            found += tokens.subList(begin, end).joinToString("") { it.text }
        }
        // Even a run that named nothing consumed its first token, so the scan always advances.
        at = maxOf(end, begin + 1)
    }
}

private fun Token.isName(): Boolean = kind == TokenKind.NAME || kind == TokenKind.QUOTED_NAME
