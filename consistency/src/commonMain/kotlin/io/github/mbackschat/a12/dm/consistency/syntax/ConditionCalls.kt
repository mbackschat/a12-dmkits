package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * The operator calls a condition or operation text makes, each with its own operand list.
 *
 * [referencesIn] answers what a text NAMES and deliberately loses where each name stood; this answers where
 * they stood. The distinction is load-bearing for any law about one operator's argument list: a group named
 * once in each of two calls is not the same group named twice in one, and A12 admits the first and refuses
 * the second (`rulekit.validate.laws.GroupListDuplicateReachLawsTest`). No scan over a flat set of names can
 * tell those apart, which is why this parses.
 *
 * A text that does not parse yields the calls read to their closing parenthesis before the failure, and
 * never one the failure cut short: A12 raises the gate of a call it has read, whatever follows it, while a
 * truncated argument list is a SHORTER list — the shape that makes an operand law look satisfied. So a law
 * that asks what the WHOLE text does or does not contain cannot read this on a failed parse; its class is a
 * post-parse one, withheld there by report assembly.
 */
fun callsIn(text: String, language: ConditionLanguage, entry: SyntaxEntry): List<ConditionCall> {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    val bodies = filterBodiesIn(text, language)
    return callShapes(text, language, entry).map { shape ->
        ConditionCall(shape.operator, shape.at, shape.operands.map { it.asOperand(keywords, language, text) },
            shape.end,
            inAFilterBody = bodies.any { body -> body.at <= shape.at && shape.end <= body.at + body.text.length })
    }
}

/** Whether a `Having` body of its own text holds this call, read as [ConditionCall.inAFilterBody] is. */
internal fun CallShape.inAFilterBody(language: ConditionLanguage): Boolean =
    filterBodiesIn(source, language).any { body -> body.at <= at && end <= body.at + body.text.length }

/**
 * One call: the operator as it was SPELLED, and its top-level arguments in authored order.
 *
 * The spelling rather than the catalog id, because that is what the text carries; a consumer that names an
 * operator resolves its id through [CONDITION_OPERATOR_KEYWORDS] for the model's own language.
 *
 * @param at the offset of the operator name.
 * @param end the offset just past the closing parenthesis: where the call has been read, which is where A12
 *     runs the operator's own check.
 * @param inAFilterBody whether a `Having` body holds the call, at any depth. A12 judges an operand gate there as
 *     outside one, with every repetition its operands cross addressed: which levels the body reads is the
 *     filter chain's question, asked after the body, the same rule a comparison follows ([ConditionComparison])
 *     and measured for every operand family (`rulekit.validate.laws.KeyedOperandFamilyGateLawsTest`).
 */
data class ConditionCall(
    val operator: String,
    val at: Int,
    val operands: List<CallOperand>,
    val end: Int,
    val inAFilterBody: Boolean = false,
)

/**
 * One top-level argument.
 *
 * @param path the entity path it names, in the same normalized spelling [referencesIn] produces and with any
 *     repetition wildcard kept — absent when the argument is anything else: a literal, an arithmetic
 *     expression, a nested call, or a reference carrying a filter, a scope or a category selector. Absent is
 *     never "names nothing"; it is "this reader did not reduce it to one path", so a law over operands must
 *     treat it as an operand it cannot compare rather than one that is not there.
 * @param base the same reduction applied to the entity spec BEFORE the first clause, so an operand carrying
 *     one still says which element it is about. Equal to [path] on a clause-free operand, and absent on the
 *     same shapes [path] is absent for.
 * @param clauses what the spec carries, in authored order — empty on a bare operand.
 * @param literal the STRING constant it is, quotes stripped and A12's doubled `""` collapsed — absent unless
 *     the operand is exactly one string token, by the same reduction rule [path] follows. An operand is never
 *     both this and [path]: a law about a value list's constants reads this and skips what it cannot reduce.
 * @param text the operand's authored text, verbatim between its first and last token. A law whose operator
 *     carries an INFIX keyword inside one argument — `NumberOfValueInFields(<constant> In <entity>)` is the
 *     one such shape — cannot use [path] or [literal], both of which reduce a multi-token run to nothing;
 *     it re-reads this instead.
 * @param constant whether the operand is a CONSTANT in A12's sense — `CheckOpUtils.isConstant`, which is one
 *     literal with any `{…}` grouping unwrapped. Narrower than A12 on purpose: only a numeric or string
 *     literal is recognized, so a temporal constant reads as false and a law counting constants under-reports
 *     rather than refusing a call A12 admits. Distinct from [literal], which answers WHICH string it is and
 *     is absent for a number.
 * @param at the offset of the operand's first token, and [end] the offset just past its last: where the
 *     argument has been read, which is where A12 runs a check owned by the argument's own production (a date
 *     slot's `checkDatumAngabe`, an entity spec's `checkEntitySpec`) rather than by the call.
 */
data class CallOperand(
    val path: String?,
    val base: String? = path,
    val clauses: List<OperandClause> = emptyList(),
    val literal: String? = null,
    val constant: Boolean = false,
    val text: String = "",
    val at: Int,
    val end: Int,
)

/**
 * One clause hanging off an entity spec: a filter, a scope, or a semantic-index key.
 *
 * **Reading a clause is what lets a family see a shape it would otherwise have to stay silent on.** Until
 * this existed every clause-carrying operand reduced to nothing at all, so a law about the `@From` scope or
 * the `Having` filter had no operand to judge — [repetitionScopeGroupFindings] is the first consumer and
 * the reason this landed.
 *
 * @param keyword the clause keyword AS SPELLED, so a consumer matches it through [CONDITION_KEYWORDS] for
 *     the model's own language rather than against a literal that is a field name in the other one.
 * @param path the clause's own operand reduced by the same bare-spec rule as [CallOperand.path] — present
 *     for a scope, absent for a filter, whose operand is a whole condition rather than a path.
 * @param text the authored text of the clause's operand, for a consumer that needs to read it further.
 * @param at the offset of [text] in the text the operand was read from, so a consumer can set exactly these
 *     characters aside; an EMPTY body sits just after its keyword. Matching [text] instead finds every run
 *     that spells it, including one outside the clause.
 * @param filtersKey for a `Having`, the semantic-index clause whose KEY it filters, or null when it filters
 *     the operand itself. A12's key is an `entitySpec`, which takes a filter of its own, so a `Having`
 *     written after an entity key is the key's — the innermost spec that can take it — while a string key has
 *     no spec to take one (`semanticIndex` and `entitySpec` in `GrammarOnly_ConditionParser.g4`; measured,
 *     `rulekit.validate.laws.FilteredOperandGateOrderLawsTest.whoseFilterAHavingAfterAnIndexKeyIs`). A
 *     family judging what a filter's BODY holds reads every `Having` alike; one judging the filtered ENTITY
 *     reads this first.
 */
data class OperandClause(
    val keyword: String,
    val path: String?,
    val text: String,
    val at: Int,
    val filtersKey: OperandClause? = null,
)

/**
 * The operand this token run describes, split at its top-level clause keywords.
 *
 * Depth matters and is the whole subtlety: a `Having` filter's body may carry its own bracketed spec with
 * its own clause, and cutting there would attribute an inner clause to the outer operand. So the scan cuts
 * only where no delimiter is open.
 */
private fun List<Token>.asOperand(
    keywords: Map<String, KeywordKind>,
    language: ConditionLanguage,
    source: String,
    cuts: List<ClauseCut> = clauseCuts(keywords),
): CallOperand {
    // An argument is recorded only once it parsed, and every argument the grammar reads takes a token.
    val at = first().start
    val end = last().end
    val text = source.substring(at, end)
    if (cuts.isEmpty()) {
        return CallOperand(asPath(keywords), literal = asLiteral(), constant = isConstant(), text = text, at = at,
            end = end)
    }
    val operators = CONDITION_OPERATOR_KEYWORDS.getValue(language)
    val bodies = cuts.mapIndexed { position, cut ->
        subList(cut.at + cut.width, cuts.getOrNull(position + 1)?.at ?: size)
    }
    var entityKey: OperandClause? = null
    val clauses = cuts.mapIndexed { position, cut ->
        val body = bodies[position]
        val clause = OperandClause(
            cut.keyword,
            body.asPath(keywords),
            if (body.isEmpty()) "" else source.substring(body.first().start, body.last().end),
            // An empty body has no first token, so its offset is where one would begin: after the keyword.
            body.firstOrNull()?.start ?: this[cut.at + cut.width - 1].end,
            filtersKey = entityKey.takeIf { cut.keyword == operators[HAVING] },
        )
        if (cut.keyword == operators[SEMANTIC_INDEX] && body.singleOrNull()?.kind != TokenKind.STRING) {
            entityKey = clause
        }
        clause
    }
    // `path` keeps its narrower contract deliberately: it has always meant "a bare spec and nothing else",
    // and widening it here would silently change every family already reading it.
    return CallOperand(null, subList(0, cuts.first().at).asPath(keywords), clauses, text = text, at = at, end = end)
}

/**
 * An operand carrying at least one clause, and where it sits in the member text.
 *
 * @param at the offset of its first token.
 * @param baseEnd the offset of its first clause keyword, so the base is exactly what sits before it.
 * @param end the offset just past its last token: where the whole entity spec, clauses included, has been
 *     read — which is where A12 asks the operand's own repetition walk
 *     (`rulekit.validate.laws.ConditionClassWithholdingLawsTest.aFilteredOperandsDefectsAnswerInTheOrderA12ReadsThem`).
 */
internal data class ClausedOperand(
    val base: String?,
    val at: Int,
    val baseEnd: Int,
    val end: Int,
    val clauses: List<OperandClause>,
)

/**
 * Every clause-carrying operand in [text], found by POSITION and without parsing the text as a whole.
 *
 * [callsIn] yields nothing for a text that does not parse, and deliberately so. A reader that has to set a
 * clause body aside cannot wait for that: A12 still judges every read ahead of the defect that ends its
 * parse, and a filter body's leaf is not one of them (the test named on [ClausedOperand.end]). So this reads
 * each call's argument list off its delimiters alone: an operator name followed by `(` opens one, a `,` at
 * the list's own depth ends an argument, and the matching `)` ends the list — or the end of the text, when
 * the list is never closed. Each argument is then cut into base and clauses exactly as [callsIn] cuts it.
 *
 * **A field-value read's `[ … ]` is read the same way**, because it holds the same `entitySpec` production a
 * call argument does, key and filter included: `[Claims/Filed For "X" Having [Claims/ClaimRef] == "a"] == 5`
 * is `MVK_WILDCARDS_REQUIRED` in A12, the filtered spec's own class, in every comparison family measured
 * (`adapter.laws.CheckerKeyedOperandAgreementTest`). Reading only call arguments left that body unblanked,
 * so its leaf was judged as the rule's own read.
 */
internal fun clausedOperandsIn(text: String, language: ConditionLanguage): List<ClausedOperand> {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    val tokens = tokenize(text).filter { it.kind != TokenKind.COMMENT }
    val operands = mutableListOf<ClausedOperand>()
    // One entry per open delimiter: where the current argument starts for a call's `(`, null for any other.
    val open = mutableListOf<Int?>()
    fun argument(from: Int?, until: Int) {
        if (from == null || from >= until) {
            return
        }
        val run = tokens.subList(from, until)
        val cuts = run.clauseCuts(keywords).ifEmpty { return }
        val operand = run.asOperand(keywords, language, text, cuts)
        operands += ClausedOperand(operand.base, run.first().start, run[cuts.first().at].start, run.last().end,
            operand.clauses)
    }
    tokens.forEachIndexed { index, token ->
        if (token.kind != TokenKind.PUNCTUATION) {
            return@forEachIndexed
        }
        when (token.text) {
            in OPENING_DELIMITERS -> {
                val operator = tokens.getOrNull(index - 1)
                val call = token.text == "(" && operator != null && operator.kind == TokenKind.NAME &&
                    operator.text in keywords
                open += if (call || token.text == "[") index + 1 else null
            }
            in CLOSING_DELIMITERS -> if (open.isNotEmpty()) argument(open.removeAt(open.lastIndex), index)
            "," -> if (open.lastOrNull() != null) {
                argument(open.last(), index)
                open[open.lastIndex] = index + 1
            }
        }
    }
    open.asReversed().forEach { argument(it, tokens.size) }
    return operands
}

/**
 * [text] with every region A12 reads as a filter condition blanked IN PLACE, one space per character.
 *
 * A12's `inFilterCondition` is ONE boolean, set as a `Having` body begins and cleared as ANY body ends
 * (`Lenient_ConditionParser.initFilterCondition` / `resetFilterCondition`, source-read). So a body is not one
 * region: once a filter nested in it ends, the rest of the outer body is read as the rule's own text. An
 * unstarred read of the filtered level draws `MVK_NO_WILDCARD` written after a nested filter and nothing
 * written before one (`adapter.laws.CheckerFilterBodyAgreementTest`, [KERNEL-FINDINGS KF486]).
 *
 * In place, so an offset into the result is an offset into [text] and a finding read off it points at the
 * author's characters. By POSITION rather than by matching the body's text, which would also blank a
 * conjunct that happens to spell it — a read A12 still judges
 * (`rulekit.validate.laws.FilterBodyTextRepeatedOutsideLawsTest`). And found by [clausedOperandsIn], so a
 * text that does not parse has its bodies set aside too.
 */
internal fun withoutFilterConditions(text: String, language: ConditionLanguage): String {
    val filter = CONDITION_OPERATOR_KEYWORDS[language]?.get(HAVING) ?: return text
    val bodies = clausedOperandsIn(text, language).flatMap { operand -> operand.clauses }.filter { it.keyword == filter }
    val switches = bodies.flatMap { listOf(it.at to true, it.at + it.text.length to false) }.sortedBy { it.first }
    val blanked = text.toCharArray()
    var since: Int? = null
    switches.forEach { (at, on) ->
        when {
            on -> if (since == null) since = at
            else -> since?.let { blanked.fill(' ', it, at) }.also { since = null }
        }
    }
    return blanked.concatToString()
}

/**
 * [text] with every region blanked IN PLACE except the specs A12 checks against the RULE's target.
 *
 * `CheckEntityUtils.checkIndicesForA12Model` skips a spec while the filter flag is on, so [withoutFilterConditions]
 * is the starting point, and two kinds of spec written inside a body are restored (source-read, measured by
 * `adapter.laws.CheckerFilterBodyAgreementTest`):
 *
 * - a `$`-marked one, which `CheckEntitySpecImpl.checkCommon` checks with the rule's error field restored, so it
 *   is judged exactly as the same read outside any filter is ([KERNEL-FINDINGS KF487]);
 * - a filtered base, which is checked once its own filter has ended and cleared the one flag, so a base written
 *   inside another filter's body is the rule's read too ([KERNEL-FINDINGS KF486]).
 */
internal fun rulesOwnReadsIn(text: String, language: ConditionLanguage): String {
    val filter = CONDITION_OPERATOR_KEYWORDS[language]?.get(HAVING) ?: return text
    val restored = withoutFilterConditions(text, language).toCharArray()
    val bases = clausedOperandsIn(text, language)
        .filter { operand -> operand.clauses.any { it.keyword == filter } }
        .map { it.at until it.baseEnd }
    (bases + markedReferenceSpansIn(text).map { it.start until it.end })
        .forEach { range -> range.forEach { restored[it] = text[it] } }
    return restored.concatToString()
}

/**
 * The string constant this run is, or null when it is anything else.
 *
 * A12 writes an embedded quote as a doubled one, and the tokenizer keeps the literal verbatim, so the two
 * unwrappings happen here rather than at each consumer — a law comparing a constant against a declared
 * domain compares the VALUE, and `"A""B"` names `A"B`.
 */
private fun List<Token>.asLiteral(): String? {
    val token = singleOrNull()?.takeIf { it.kind == TokenKind.STRING } ?: return null
    return token.text.removeSurrounding("\"").replace("\"\"", "\"")
}

/**
 * Whether this run is one constant, which is what A12's `CheckOpUtils.isConstant` asks of an operand.
 *
 * One `{…}` pair is unwrapped, as the kernel's `BRACKET_OP` arm does, and only one pair: a deeper nesting
 * reads as false, which costs a law nothing it may not lose. A decimal arrives as three tokens because the
 * tokenizer's number terminal is a digit RUN, so the sign and the fraction are matched here rather than in it.
 */
private fun List<Token>.isConstant(): Boolean {
    val core = if (size >= 2 && first().text == "{" && last().text == "}") subList(1, size - 1) else this
    if (core.size == 1 && core[0].kind == TokenKind.STRING) {
        return true
    }
    val digits = if (core.firstOrNull()?.text == "-" || core.firstOrNull()?.text == "+") {
        core.subList(1, core.size)
    } else {
        core
    }
    return when (digits.size) {
        1 -> digits[0].kind == TokenKind.NUMBER
        3 -> digits[0].kind == TokenKind.NUMBER && digits[1].text == "." && digits[2].kind == TokenKind.NUMBER
        else -> false
    }
}

/** Where one clause keyword sits, and how many tokens it spans. */
private class ClauseCut(val keyword: String, val at: Int, val width: Int)

/**
 * The top-level clause keywords in this run, in order.
 *
 * `@From` / `@Von` is the one that does not arrive as a single token — the tokenizer files `@` as
 * punctuation because it is also the `@*` marker — so it is matched as the adjacent pair, exactly as the
 * shape parser and the reference scan both do.
 */
private fun List<Token>.clauseCuts(keywords: Map<String, KeywordKind>): List<ClauseCut> {
    val cuts = mutableListOf<ClauseCut>()
    var depth = 0
    indices.forEach { at ->
        val token = this[at]
        when {
            token.kind == TokenKind.PUNCTUATION && token.text in OPENING_DELIMITERS -> depth++
            token.kind == TokenKind.PUNCTUATION && token.text in CLOSING_DELIMITERS -> depth--
            depth > 0 -> Unit
            token.kind == TokenKind.NAME && keywords[token.text] == KeywordKind.PATH_OP ->
                cuts += ClauseCut(token.text, at, 1)
            token.kind == TokenKind.PUNCTUATION && token.text == "@" -> {
                val name = getOrNull(at + 1)
                if (name != null && name.kind == TokenKind.NAME &&
                    keywords["@" + name.text] == KeywordKind.PATH_OP
                ) {
                    cuts += ClauseCut("@" + name.text, at, 2)
                }
            }
        }
    }
    return cuts
}

private val OPENING_DELIMITERS = setOf("(", "[")

private val CLOSING_DELIMITERS = setOf(")", "]")

/**
 * The path this token run names, or null when the run is not a bare entity spec.
 *
 * Bare means the run is path syntax and nothing else, so a clause keyword (`Having`, `@From`, `->`), a
 * bracket, an operator symbol or a literal all disqualify it — each of those makes the argument something
 * whose identity is not the path inside it. The `$` outer-repetition marker disqualifies it too: the marker
 * selects a different repetition of the same element, which is a distinction paths do not carry.
 */
private fun List<Token>.asPath(keywords: Map<String, KeywordKind>): String? {
    forEach { token ->
        when {
            token.kind == TokenKind.NAME && token.text in keywords -> return null
            token.isName() || token.kind == TokenKind.NUMBER || token.text in PATH_PUNCTUATION -> Unit
            else -> return null
        }
    }
    return if (namesAnEntity()) normalizedPath(this) else null
}

/**
 * This call's operands split at its infix `In`, as authored text, or null when it carries none.
 *
 * Two operator families put an `In` between two comma-separated lists — `NumberOfValueInFields(<constant> In
 * <entities>)` and the value-list quantifiers `…(<fields> In <values>)` — and the keyword sits INSIDE one
 * operand, because only a comma splits operands. Neither [CallOperand.path] nor [CallOperand.literal]
 * survives a multi-token run, so a law about either list has to re-read the text; doing it here keeps one
 * scanner rather than one per family.
 *
 * The keyword needs no per-language lookup: the grammar's `inOp` admits `In`, `IN` and `in` and nothing
 * else, so unlike an operator name it is the same in every rendering.
 */
fun ConditionCall.splitAtIn(): Pair<List<String>, List<String>>? = splitAtInfix("In")

/**
 * The same split at an arbitrary infix keyword, for the productions whose separator is not `In`.
 *
 * `ValueNotConsistent(<field> To <values>)` puts a `To` where those put an `In`, and `To` — unlike `In` —
 * IS language-dependent (`Zu` in a German model), so the caller supplies the spelling its language uses.
 * Matching is case-insensitive because every one of these grammar terminals admits all three casings.
 */
fun ConditionCall.splitAtInfix(keyword: String): Pair<List<String>, List<String>>? {
    val at = operands.indexOfFirst { infixAt(it.text, keyword) != null }
    if (at < 0) {
        return null
    }
    val cut = infixAt(operands[at].text, keyword) ?: return null
    val left = operands.take(at).map { it.text } + operands[at].text.substring(0, cut.first).trim()
    val right = listOf(operands[at].text.substring(cut.second).trim()) + operands.drop(at + 1).map { it.text }
    return left.filter { it.isNotEmpty() } to right.filter { it.isNotEmpty() }
}

/** Where a top-level [keyword] starts and ends in one operand's text, or null when it carries none. */
private fun infixAt(text: String, keyword: String): Pair<Int, Int>? {
    var depth = 0
    tokenize(text).forEach { token ->
        when {
            token.kind == TokenKind.PUNCTUATION && token.text in OPENING_DELIMITERS -> depth++
            token.kind == TokenKind.PUNCTUATION && token.text in CLOSING_DELIMITERS -> depth--
            depth == 0 && token.kind == TokenKind.NAME && token.text.equals(keyword, ignoreCase = true) ->
                return token.start to token.end
        }
    }
    return null
}

/**
 * The comparisons a condition text makes, each with the authored text of both its sides.
 *
 * A comparison is the one condition shape that is not a call, so [callsIn] cannot see it and no law about
 * `vergleichsBedingung` — A12's largest single check method, thirteen classes — had an operand structure to
 * read. Both sides arrive as TEXT rather than reduced: a law about a comparison asks first what each side
 * IS, and a reduction that answered "a path" for a bare reference and null for everything else would erase
 * the distinction between a literal, arithmetic and a nested call, which those laws turn on.
 *
 * A text that does not parse yields the comparisons read to the end of their right side before the failure,
 * by the contract [callsIn] states.
 */
fun comparisonsIn(text: String, language: ConditionLanguage, entry: SyntaxEntry): List<ConditionComparison> {
    val bodies = filterBodiesIn(text, language)
    return comparisonShapes(text, language, entry).map { shape ->
        val start = shape.left.first().start
        val end = shape.right.last().end
        ConditionComparison(
            runText(shape.left, text),
            shape.operator.text,
            shape.operator.start,
            runText(shape.right, text),
            start = start,
            end = end,
            inAFilterBody = bodies.any { body -> body.at <= start && end <= body.at + body.text.length },
        )
    }
}

/**
 * One comparison in authored order.
 *
 * @param left the text left of the operator, verbatim between its first and last token.
 * @param operator the comparison operator AS SPELLED — a symbol, or a keyword a consumer resolves through
 *     [CONDITION_OPERATOR_KEYWORDS] for the model's own language.
 * @param at the offset of the operator, so a finding can point at the comparison rather than at the rule.
 * @param right the text right of the operator, by the same rule as [left].
 * @param start the offset of the left side's first token, and [end] the offset just past the right side's
 *     last: where the comparison has been read, which is where A12 runs `checkVergleichsBedingung`.
 * @param inAFilterBody whether a `Having` body holds the comparison, at any depth. A12 judges it there as
 *     outside one, and before it asks which repetitions the body reads: that is the filter chain's question,
 *     raised once the whole spec is read, so a side crossing a level no star addresses is still typed — the
 *     filtered level or any other, the enclosing filter's or a nested one's
 *     (`rulekit.validate.laws.FilterBodyComparisonLawsTest`).
 */
data class ConditionComparison(
    val left: String,
    val operator: String,
    val at: Int,
    val right: String,
    val start: Int,
    val end: Int,
    val inAFilterBody: Boolean = false,
)

/**
 * Every field-value read a text makes: A12's `feldWertOp`, `'[' entitySpec ']'`, which is the grammar's only
 * use of a square bracket (`GrammarOnly_ConditionParser.g4`), so every matched pair of brackets is one.
 *
 * A bracket A12 never closes is not read, since its production never ends and its gate is never asked; the
 * text's own parse failure is another family's.
 */
internal fun fieldValueReadsIn(text: String, language: ConditionLanguage): List<FieldValueRead> {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    val bodies = filterBodiesIn(text, language)
    val tokens = tokenize(text).filter { it.kind != TokenKind.COMMENT }
    val open = ArrayDeque<Int>()
    val reads = mutableListOf<FieldValueRead>()
    tokens.forEachIndexed { index, token ->
        if (token.kind != TokenKind.PUNCTUATION) {
            return@forEachIndexed
        }
        when (token.text) {
            "[" -> open.addLast(index)
            "]" -> {
                val from = open.removeLastOrNull() ?: return@forEachIndexed
                val at = tokens[from].start
                val spec = tokens.subList(from + 1, index)
                val category = spec.takeIf { it.size > 2 && it[it.size - 2].text == "->" && it.last().isName() }
                    ?.last()?.text
                reads += FieldValueRead(
                    (if (category == null) spec else spec.dropLast(2)).asPath(keywords),
                    at,
                    token.end,
                    inAFilterBody = bodies.any { body -> body.at <= at && token.end <= body.at + body.text.length },
                    category = category,
                )
            }
        }
    }
    return reads
}

/**
 * The field-value read [text] consists of, or null when the text is anything more or less than one bracket.
 *
 * For a reader whose subject is a whole operation that A12 lowers to one `feldWertOp`: the bracket's own path
 * reader spells every path the grammar admits, a climbing `..` included, where a bare-spec reader need not.
 */
internal fun soleFieldValueReadIn(text: String, language: ConditionLanguage): FieldValueRead? {
    val tokens = tokenize(text).filter { it.kind != TokenKind.COMMENT }
    val read = fieldValueReadsIn(text, language).singleOrNull() ?: return null
    return read.takeIf { tokens.firstOrNull()?.start == it.at && tokens.lastOrNull()?.end == it.end }
}

/**
 * One field-value read.
 *
 * @param path the entity it names, in the normalized spelling [referencesIn] produces with any repetition
 *     wildcard kept — absent when the spec is not one bare path, or one path and a category: it carries a
 *     clause, the `$` outer marker or the rule-context keyword, each of which brings another gate ahead of
 *     this one.
 * @param at the offset of the opening bracket, and [end] the offset just past the closing one: where A12 has
 *     read the production and runs `CheckFeldWertOpImpl.check`.
 * @param inAFilterBody whether a `Having` body holds the read, at any depth.
 * @param category the name behind a `->` the spec ends with, or null. A category belongs to the entity spec
 *     inside the brackets (`entitySpec: … entity ('->' categoryName)? …`), whose own gates run when the spec
 *     ends, before the bracket's.
 */
internal data class FieldValueRead(
    val path: String?,
    val at: Int,
    val end: Int,
    val inAFilterBody: Boolean,
    val category: String? = null,
)

/** Every `Having` body in [text], each placed by its own offset. */
private fun filterBodiesIn(text: String, language: ConditionLanguage): List<OperandClause> {
    val filter = CONDITION_OPERATOR_KEYWORDS.getValue(language)[HAVING]
    return clausedOperandsIn(text, language).flatMap { operand -> operand.clauses.filter { it.keyword == filter } }
}

private fun runText(run: List<Token>, source: String): String =
    if (run.isEmpty()) "" else source.substring(run.first().start, run.last().end)
