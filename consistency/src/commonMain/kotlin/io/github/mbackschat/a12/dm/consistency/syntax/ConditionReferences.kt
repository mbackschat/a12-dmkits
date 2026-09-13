package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * The entities a condition or operation text names, in the order they first appear.
 *
 * Each result is the reference in NORMALIZED spelling: insignificant whitespace removed, a quoted name's
 * quotes stripped, an up-run separated. That is what binding needs, and it is deliberately not the authored
 * text — a report that wants the exact bytes reads the tokens and their spans.
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
 * different one. A `..Name` turning point is consumed as one upward step and its LABEL is not checked
 * against the ancestor it names — A12 does check it, so this under-reports and never invents a rejection.
 * And nothing here decides whether the text parses; a malformed condition yields whatever names it contains.
 */
fun referencesIn(text: String, language: ConditionLanguage): List<String> =
    ReferenceScan(
        tokenize(text),
        CONDITION_KEYWORDS.getValue(language),
        CONDITION_OPERATOR_KEYWORDS.getValue(language)[CUSTOM_CONDITION],
    ).references()

/**
 * Whether the text names the RULE-CONTEXT entity — A12's `RuleGroup` / `RegelKontext`.
 *
 * [referencesIn] cannot report it, because it names no path: what it resolves to is the declaring element's
 * own group, which only the caller knows. So a consumer whose law turns on reach asks this and resolves the
 * spelling itself.
 *
 * Settled per [language] like every other keyword question here: `RegelKontext` in an English model is an
 * ordinary field name, so a fixed pair of literals would silently claim a reach the model does not have.
 */
internal fun namesRuleContext(text: String, language: ConditionLanguage): Boolean {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    return tokenize(text).any {
        it.kind == TokenKind.NAME && it.text in keywords && it.text in RULE_CONTEXT_SPELLINGS
    }
}

/**
 * The catalog id of the one predicate spelled as a keyword followed by a BARE NAME rather than a call.
 *
 * Resolved to the model's own language through [CONDITION_OPERATOR_KEYWORDS] rather than written as a literal,
 * because the German spelling is a different word (`ApplikationsBedingung`).
 * `ConditionReferenceTest.theNameAfterTheCustomConditionKeywordIsNotAReference` fails in both languages if the
 * catalog ever stops carrying it, so the lookup cannot degrade silently back into the over-rejection.
 */
private const val CUSTOM_CONDITION = "CustomCondition"

/** Path syntax that continues a reference: the separator, the two step forms, and the repetition wildcard. */
internal val PATH_PUNCTUATION = setOf("/", "..", ".", "*")

/** What a reference may end with — a trailing separator is a malformed tail, never part of the name. */
private val PATH_SEPARATORS = setOf("/", ".")

private class ReferenceScan(
    private val tokens: List<Token>,
    private val keywords: Map<String, KeywordKind>,
    private val customCondition: String?,
) {

    private var at = 0
    private val found = LinkedHashSet<String>()

    fun references(): List<String> {
        while (at < tokens.size) {
            val keyword = keywordLength()
            when {
                keyword > 0 -> at += keyword + boundNameLength(keyword)
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
     * Those two ARE adjacency-sensitive, being one lexer token rather than a path built from several.
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
     * One more token when this keyword is `CustomCondition`, which takes a BARE NAME instead of a call.
     *
     * `CustomCondition <Name>` names host code the consuming system registered, not an element
     * (KERNEL-SEMANTICS §14), so resolving that name reports an undeclared field on every model using the
     * escape hatch — measured on three committed models the Kernel builds
     * (`adapter.laws.CheckerSilenceOnCleanModelsTest`).
     *
     * **Only this one operator, matched by its own spelling.** The SHAPE parser reaches the same conclusion
     * from a broader test — any callable-condition keyword not followed by `(` — and copying that test here
     * was wrong and measured wrong: the parser applies it only where a condition is expected positionally,
     * while this is a flat token walk, so the broad form swallowed the operand after the UNCATALOGED infix
     * `In` — leaving the starred group's own name eaten and the rest of the path read as an absolute
     * reference to its trailing segment. The reference corpus reported it on nine rules across five models
     * within minutes.
     */
    private fun boundNameLength(keywordLength: Int): Int {
        if (tokens[at].text != customCondition) {
            return 0
        }
        val following = tokens.getOrNull(at + keywordLength) ?: return 0
        return if (following.isName()) 1 else 0
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
     * Read the run of path tokens starting at [at], ignoring whitespace between them.
     *
     * **Whitespace is on the lexer's HIDDEN channel and is insignificant between ANY two tokens**
     * ([KERNEL-FINDINGS KF76]), so a space between `Group*` and its separator changes nothing, and `.. / seg`
     * is `../seg`. An earlier
     * version used adjacency as the discriminator against arithmetic and was wrong for exactly that reason.
     * The real discriminator is the one KF76 names: a bare identifier is never an arithmetic operand — an
     * operand is bracketed or parenthesized — so `/` and `*` following a bare path segment are always path
     * syntax, and a run that names nothing (`[A] / [B]`, where `]` ends it) yields no reference at all.
     */
    private fun readReference() {
        val begin = at
        var end = at
        var names = 0
        while (end < tokens.size) {
            val token = tokens[end]
            when {
                // A keyword ends the run: unquoted, the Kernel lexes it as the keyword, so it is not a segment.
                token.kind == TokenKind.NAME && token.text in keywords -> break
                token.isName() -> names++
                token.kind == TokenKind.NUMBER || token.text in PATH_PUNCTUATION -> Unit
                else -> break
            }
            end++
        }
        while (end > begin && tokens[end - 1].text in PATH_SEPARATORS) end--
        if (names > 0) {
            found += normalizedPath(tokens.subList(begin, end))
        }
        // Even a run that named nothing consumed its first token, so the scan always advances.
        at = maxOf(end, begin + 1)
    }
}

internal fun Token.isName(): Boolean = kind == TokenKind.NAME || kind == TokenKind.QUOTED_NAME

/**
 * Render the run as one path.
 *
 * The one insertion is a separator between two consecutive `..`: whitespace inside an up-run is insignificant
 * too (`.. ..` is `....`, KF76), and concatenating them would produce a dot-run no path split can read back.
 */
internal fun normalizedPath(run: List<Token>): String {
    val text = StringBuilder()
    var previousWasUp = false
    run.forEach { token ->
        val up = token.text == UP_STEP
        if (up && previousWasUp) text.append('/')
        text.append(token.text)
        previousWasUp = up
    }
    return text.toString()
}

private const val UP_STEP = ".."

