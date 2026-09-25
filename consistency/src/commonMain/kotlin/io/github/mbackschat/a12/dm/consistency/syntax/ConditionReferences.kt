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
fun referencesIn(text: String, language: ConditionLanguage): List<String> = scanOf(text, language).references()

/**
 * The same references with the characters each one occupies — what a report needs to point at one.
 *
 * Every OCCURRENCE is reported, in text order, where [referencesIn] reports each name once: a consumer
 * underlining a name wants all of them, and a consumer binding one wants the name. Both come from a single
 * scan, so asking for spans costs no second tokenization.
 *
 * The offsets are half-open over [text] and span the whole path run, `..` steps and repetition markers
 * included — the run a reader has to edit, not the normalized spelling [ReferenceSpan.written] carries.
 */
fun referenceSpansIn(text: String, language: ConditionLanguage): List<ReferenceSpan> =
    scanOf(text, language).referenceSpans()

/** One reference occurrence: its normalized spelling, and the characters it occupies. */
data class ReferenceSpan(val written: String, val start: Int, val end: Int)

/**
 * Each name [text] references once, at its FIRST occurrence — [referencesIn] with the characters attached.
 *
 * The first is the one a finding points at: A12 reports the first defect in a text and nothing after it, so
 * a later spelling of the same name is never where it stopped.
 */
internal fun firstReferenceSpansIn(text: String, language: ConditionLanguage): List<ReferenceSpan> =
    referenceSpansIn(text, language).distinctBy { it.written }

private fun scanOf(text: String, language: ConditionLanguage) = ReferenceScan(
    tokenize(text),
    CONDITION_KEYWORDS.getValue(language),
    CONDITION_OPERATOR_KEYWORDS.getValue(language)[CUSTOM_CONDITION],
)

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
internal fun namesRuleContext(text: String, language: ConditionLanguage): Boolean =
    tokenize(text).any { it.kind == TokenKind.NAME && isRuleContext(it.text, language) }

/**
 * The references standing in the KEY position of a semantic index — the operand right after [keyword] —
 * each where it is written, in text order.
 *
 * [referencesIn] reports a key like any other name, which is right for binding and useless for a law about
 * the key POSITION: `[Coverages/Limit For Insured]` names two entities and only the second one is subject to
 * the key rules. So this runs the same scan and keeps the references that BEGIN where the keyword left off.
 *
 * A LITERAL key yields nothing, which is the correct answer rather than a gap: `[… For "C-1"]` puts a string
 * in that position, and every key-kind law is about an entity key.
 *
 * [keyword] arrives already resolved for the model's condition language, since `For` and `Fuer` are different
 * words and only the catalog knows which one this model speaks.
 */
internal fun semanticIndexKeysIn(text: String, language: ConditionLanguage, keyword: String): List<ReferenceSpan> =
    ReferenceScan(
        tokenize(text),
        CONDITION_KEYWORDS.getValue(language),
        CONDITION_OPERATOR_KEYWORDS.getValue(language)[CUSTOM_CONDITION],
        keyword,
    ).semanticIndexKeys()

/**
 * The `->` CATEGORY accesses in the text, each as the reference it hangs off and the name behind the arrow.
 *
 * [referencesIn] cannot report either half. The name after the arrow is stepped over deliberately — it is a
 * category attribute rather than an entity, so resolving it would report every categorized reference as
 * undeclared — and the PAIRING is what a law about the category needs: `[Tier -> Band]` is legal exactly
 * when `Tier`'s own Enumeration declares `Band`, so neither half means anything alone.
 *
 * An arrow behind something this reader did not record as a reference yields nothing, which is the correct
 * answer rather than a gap: A12 answers such text with a class about the operand, never about the category.
 */
internal fun categoryAccessesIn(text: String, language: ConditionLanguage): List<CategoryAccess> =
    ReferenceScan(
        tokenize(text),
        CONDITION_KEYWORDS.getValue(language),
        CONDITION_OPERATOR_KEYWORDS.getValue(language)[CUSTOM_CONDITION],
    ).categoryAccesses()

/**
 * One `->` category access: the reference it hangs off, the name behind the arrow, and where the pair is
 * written — from the reference's first character to the category name's last.
 */
internal data class CategoryAccess(val path: String, val category: String, val start: Int, val end: Int)

/**
 * The first place the text puts a repetition wildcard on the RULE-CONTEXT entity — the keyword and its star —
 * or null when it puts none.
 *
 * A12 refuses that with a class of its own, `MVK_INVALID_WILDCARD_FOR_RULEGROUP`
 * (`rulekit.validate.laws.StarredNonRepeatableReachLawsTest`). It needs its own question for the same reason
 * [namesRuleContext] does — the spelling is a keyword, so it never reaches a consumer through [referencesIn],
 * and a family judging starred references alone would stay silent on a shape A12 rejects.
 *
 * The test is on the TOKEN, so a QUOTED `'RuleGroup'` is correctly not this: quoting is A12's escape for a
 * name that collides with a keyword, so it names a declared group and is judged as one.
 */
internal fun starredRuleContextIn(text: String, language: ConditionLanguage): ReferenceSpan? {
    val tokens = tokenize(text)
    val at = tokens.indices.firstOrNull { at ->
        tokens[at].kind == TokenKind.NAME && isRuleContext(tokens[at].text, language) &&
            tokens.getOrNull(at + 1)?.text == "*"
    } ?: return null
    return ReferenceSpan("${tokens[at].text}*", tokens[at].start, tokens[at + 1].end)
}

/**
 * Whether [name] spells the rule-context entity in [language].
 *
 * Both halves are load-bearing: the spelling must be one of the two A12 uses, AND it must be a keyword in
 * THIS language, because each spelling is an ordinary field name in the other one.
 */
internal fun isRuleContext(name: String, language: ConditionLanguage): Boolean =
    name in RULE_CONTEXT_SPELLINGS && name in CONDITION_KEYWORDS.getValue(language)

/** Path syntax that continues a reference: the separator, the two step forms, and the repetition wildcard. */
internal val PATH_PUNCTUATION = setOf("/", "..", ".", "*")

/** What a reference may end with — a trailing separator is a malformed tail, never part of the name. */
private val PATH_SEPARATORS = setOf("/", ".")

/**
 * Whether a run of path tokens names an entity: it carries a name, or an up-step, which names an ancestor
 * group on its own. A run of separators and stars alone — the `/` of `[A] / [B]` — names nothing.
 *
 * Keeping only a run with a NAME in it left `..`, `../..` and `/..` unread, so no family saw them and the
 * checker was silent on every rejection A12 draws for them (`rulekit.validate.laws.PathSegmentWalkLawsTest`).
 */
internal fun List<Token>.namesAnEntity(): Boolean = any { it.isName() || it.text == UP_STEP }

private class ReferenceScan(
    private val tokens: List<Token>,
    private val keywords: Map<String, KeywordKind>,
    private val customCondition: String?,
    private val semanticIndex: String? = null,
) {

    private var at = 0
    private val found = LinkedHashSet<String>()
    private val spans = mutableListOf<ReferenceSpan>()
    private val keys = mutableListOf<ReferenceSpan>()
    private val categories = mutableListOf<CategoryAccess>()

    /** The reference the scan read most recently — what an arrow immediately after it hangs off. */
    private var lastReference: ReferenceSpan? = null

    /** Where a reference would have to begin to be a semantic-index key: right after the last such keyword. */
    private var keyBegins = -1

    fun semanticIndexKeys(): List<ReferenceSpan> {
        references()
        return keys.toList()
    }

    /** Every occurrence, in text order — the list [found] deduplicates away. */
    fun referenceSpans(): List<ReferenceSpan> {
        references()
        return spans.toList()
    }

    fun categoryAccesses(): List<CategoryAccess> {
        references()
        return categories.toList()
    }

    fun references(): List<String> {
        while (at < tokens.size) {
            val keyword = keywordLength()
            when {
                keyword > 0 -> {
                    if (semanticIndex != null && tokens[at].text == semanticIndex) {
                        keyBegins = at + keyword
                    }
                    at += keyword + boundNameLength(keyword) + formSpecLength(keyword)
                }
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
     * The parenthesized argument of an H-model FORM construct, which names a form and never an entity.
     *
     * The grammar reads it as `formularSpec : nameOhnePunkt formularEbenenSpecHModel` — a bare name and a
     * level specifier, with no reference check anywhere on it — for all seven constructs in
     * [FORM_CONSTRUCTS], so resolving it reported a class A12 never draws beside the construct's own
     * (`rulekit.validate.laws.HModelFormConditionReachabilityLawsTest.theArgumentIsNeverReadAsAnEntity`).
     * Only a spelling the model's language lexes as the keyword counts: in an English model the same text is
     * an ordinary name, refused by the parser.
     */
    private fun formSpecLength(keywordLength: Int): Int {
        if (tokens[at].text !in FORM_CONSTRUCTS || tokens.getOrNull(at + keywordLength)?.text != "(") {
            return 0
        }
        val close = (at + keywordLength until tokens.size).firstOrNull { tokens[it].text == ")" }
        return (close ?: tokens.lastIndex) - (at + keywordLength) + 1
    }

    /**
     * Step over `->` and the name behind it. That name is a value CATEGORY
     * (`entitySpec … ('->' categoryName=nameOhnePunkt)?`), so resolving it as an entity would report every
     * categorized reference as undeclared.
     */
    private fun skipCategoryName() {
        at++
        if (at < tokens.size && tokens[at].isName()) {
            lastReference?.let { categories += CategoryAccess(it.written, tokens[at].text, it.start, tokens[at].end) }
            at++
        }
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
        while (end < tokens.size) {
            val token = tokens[end]
            when {
                // A keyword ends the run: unquoted, the Kernel lexes it as the keyword, so it is not a segment.
                token.kind == TokenKind.NAME && token.text in keywords -> break
                token.isName() || token.kind == TokenKind.NUMBER || token.text in PATH_PUNCTUATION -> Unit
                else -> break
            }
            end++
        }
        while (end > begin && tokens[end - 1].text in PATH_SEPARATORS) end--
        if (tokens.subList(begin, end).namesAnEntity()) {
            val path = normalizedPath(tokens.subList(begin, end))
            found += path
            val span = ReferenceSpan(path, tokens[begin].start, tokens[end - 1].end)
            spans += span
            lastReference = span
            if (begin == keyBegins) {
                keys += span
            }
        }
        // Even a run that named nothing consumed its first token, so the scan always advances.
        at = maxOf(end, begin + 1)
    }
}

internal fun Token.isName(): Boolean = kind == TokenKind.NAME || kind == TokenKind.QUOTED_NAME

/**
 * The seven H-model form constructs, each spelled as the German bundle spells it — the only language that
 * lexes them as keywords.
 *
 * Six are the alternatives of the grammar's `formularBedingungName`; the seventh is the count operation
 * `anzahlAngegebenOpHModelName`, which takes the same `formularSpec` argument.
 */
internal val FORM_CONSTRUCTS = setOf(
    "VordruckAngegeben",
    "VordruckNichtAngegeben",
    "KeinVordruckAngegeben",
    "MindestensEinVordruckAngegeben",
    "VordruckNichtFortlaufend",
    "UsbNichtFortlaufend",
    "AnzahlAngegebenerVordrucke",
)

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

/**
 * The first of [spellings] that [text] uses as a KEYWORD, or null.
 *
 * A quoted name is a different token kind, so an entity deliberately called `RepetitionNotUnique` is not a
 * use of the predicate — which is the whole reason this goes through the tokenizer instead of scanning for a
 * substring. [spellings] arrive already resolved for the model's condition language, since that is where the
 * id-to-spelling table lives; re-testing them against [CONDITION_KEYWORDS] here would add a branch no model
 * can take, because every spelling in [CONDITION_OPERATOR_KEYWORDS] is a keyword of that same language.
 */
internal fun namesKeyword(text: String, spellings: Set<String>): String? =
    tokenize(text).firstOrNull { it.kind == TokenKind.NAME && it.text in spellings }?.text
