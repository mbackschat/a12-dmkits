package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether condition or operation text FORMS a condition at all — A12's remaining two parse codes.
 *
 * This is the grammatical phase, and it is deliberately **looser than A12's grammar**. The kernel gives each
 * operator its own production with its own argument shape (`Datum` takes two to four date parameters,
 * `FeldWertInWerteListe` takes a field and then a value list); reproducing all of them would be a second
 * implementation of the catalog, and every place it disagreed would REJECT a condition A12 admits. So this
 * checks the shape the productions share — a call is a name and a parenthesised argument list, an argument is
 * an expression — and leaves per-operator arity and operand kinds to the Kernel. The cost is silence on a
 * wrong-arity call; the alternative cost was over-rejection, which is the one direction this checker may not
 * take.
 *
 * **One thing every production shares IS checked: where a bare entity reference may stand.** No operation and
 * no condition begins with a name that is not a keyword, so a bare entity stands only where an `entitySpec`
 * does — an operator's argument, a clause's operand, a field-value bracket — and anywhere else it is a token
 * the parser cannot use ([KERNEL-FINDINGS KF472]). That is grammar every operator shares, not one operator's
 * argument shape, so it costs no over-rejection.
 *
 * **What decides the code is where the parser stopped**, which is the one thing [KF300]'s two withdrawn
 * grammar-free rules could not know and a parser does:
 *
 * | the parser… | code |
 * |---|---|
 * | met a token it cannot use where it stands — including the end of the text with every delimiter closed | `MVK_UNEXPECTED_TOKEN` |
 * | ran out of text where exactly one token may follow: a delimiter still open, or a sign with no number yet | `MVK_EXPECTED_TOKEN_NOT_FOUND` |
 *
 * Both halves are measured. `FieldFilled(Tier` is the only shape of the delimiter grid that runs out with
 * something open, and it is the only one A12 answers with `MVK_EXPECTED_TOKEN_NOT_FOUND`; the other four meet
 * a wrong token first and are all `MVK_UNEXPECTED_TOKEN`. A bare `Tier` reaches the end with nothing open and
 * is `MVK_UNEXPECTED_TOKEN` too, which is why the end of the text is not by itself a "ran out". `[Premium] > -`
 * runs out with nothing open and is `MVK_EXPECTED_TOKEN_NOT_FOUND` all the same, since a sign stands on a
 * number alone (`rulekit.validate.laws.DanglingOperatorPairLawsTest`): what picks the code is that one token
 * was required, of which an open delimiter is one case.
 *
 * The earlier phases own their own shapes: text that cannot be tokenized is [lexicalFailure]'s, and text that
 * ends on a dangling operator is [incompleteInput]'s. A caller runs them first — except where the parser gets
 * there first, which [shapeFailureAhead] decides.
 */
fun shapeFailure(text: String, language: ConditionLanguage, entry: SyntaxEntry): ShapeFailure? {
    val parser = ShapeParser(text, language)
    return when (entry) {
        SyntaxEntry.CONDITION -> parser.parseCondition()
        SyntaxEntry.OPERATION -> parser.parseOperation()
    }
}

/**
 * Which of A12's two entry rules parses a member's text.
 *
 * The two differ in what they accept at the top and nowhere below it: a condition must be a condition, while
 * an operation is an expression — a bare `42.00` is a legal operation and not a legal condition, so running
 * one text through the other's entry reports a defect that is not there.
 */
enum class SyntaxEntry {
    /** `bedingungSnippet` — a rule's `errorCondition` and a computation's preconditions. */
    CONDITION,

    /** `operationSnippet` — a computation alternative's `operation`. */
    OPERATION,
}

/**
 * The shape failure A12's parser meets BEFORE its lexer stops at [lexical] — null when the lexer speaks first.
 *
 * **A12 reports the failure that sits EARLIEST in the text, whichever phase owns it**, because ANTLR's lexer
 * is demand-driven: it produces a token only when the parser asks for one, so it cannot report a failure at a
 * position the parser has not reached, and the parser cannot reach a position the lexer has already refused.
 * Measured in both orders, for both lexical classes, with the two defects held fixed and only their order
 * moved ([KERNEL-FINDINGS KF311], `rulekit.validate.laws.SyntaxPhasePrecedenceLawsTest`) — including the row
 * that separates first-wins from last-wins, where the lexer skips the character it could not take and the
 * parser then fails on what is left, and A12 still names the lexer.
 *
 * So the parser is run over the text UP TO the lexical stop, which is exactly what A12's parser got to see.
 * A failure that names a token there is one A12 reaches first; one that merely runs out of tokens is not a
 * failure yet — the parser would ask for the next token, and that request is what raises the lexical failure.
 * [ShapeFailure.found] is empty in precisely that case and carries the distinction.
 */
fun shapeFailureAhead(
    text: String,
    language: ConditionLanguage,
    entry: SyntaxEntry,
    lexical: LexicalFailure,
): ShapeFailure? = shapeFailure(text.take(lexical.at), language, entry)?.takeIf { it.found.isNotEmpty() }

/**
 * Where the parser stopped and what it was holding.
 *
 * @param at the offset of the token it could not use, or the end of the text when it ran out.
 * @param ranOutAwaitingOneToken the half that picks the code — see the table on [shapeFailure].
 * @param found the token's text, or an empty string at the end of the text, so a finding can quote it.
 */
data class ShapeFailure(val at: Int, val ranOutAwaitingOneToken: Boolean, val found: String)

/**
 * One operator call the parser read, with the token run each of its top-level arguments spanned.
 *
 * Raw tokens rather than interpreted operands, because what an argument MEANS is not this file's question:
 * the grammar knows where one argument ends and the next begins, and [callsIn] decides which of them name an
 * entity. Nested calls are recorded too, each with its own arguments, innermost first.
 *
 * @param at the offset of the operator name, so a finding can point at the call rather than at the rule.
 * @param end the offset just past its closing parenthesis, where A12 runs the operator's own check.
 * @param source the text the call was read from, which every token offset points into — what [textOf] and
 *     [inAFilterBody] re-read.
 */
internal data class CallShape(
    val operator: String,
    val at: Int,
    val end: Int,
    val operands: List<List<Token>>,
    val source: String,
)

/** The authored text of one of this call's [CallShape.operands], verbatim between its first and last token. */
internal fun CallShape.textOf(operand: List<Token>): String =
    if (operand.isEmpty()) "" else source.substring(operand.first().start, operand.last().end)

/**
 * One comparison: the token runs on either side of an infix comparison operator, and the operator itself.
 *
 * A comparison is A12's own `vergleichsBedingung`, and it is the one condition shape that is not a call —
 * which is why no law about it could be written until this existed. Both sides are recorded as token runs
 * rather than reduced, because what a side IS (a bare reference, a literal, arithmetic, a nested call) is
 * the question most comparison laws ask first.
 */
internal data class ComparisonShape(
    val left: List<Token>,
    val operator: Token,
    val right: List<Token>,
)

/** Every comparison in [text] read to the end of its right side, a failed parse included: [callShapes]' contract. */
internal fun comparisonShapes(
    text: String,
    language: ConditionLanguage,
    entry: SyntaxEntry,
): List<ComparisonShape> {
    val comparisons = mutableListOf<ComparisonShape>()
    val parser = ShapeParser(text, language, comparisons = comparisons)
    when (entry) {
        SyntaxEntry.CONDITION -> parser.parseCondition()
        SyntaxEntry.OPERATION -> parser.parseOperation()
    }
    return comparisons
}

/**
 * Every call in [text] read to its closing parenthesis, including those a failed parse read before failing.
 *
 * A12 runs an operator's check once its call is read, whatever follows it, so a call the parser completed
 * ahead of the defect that ends the parse is one whose gate A12 raises
 * (`rulekit.validate.laws.ConditionClassWithholdingLawsTest.aGateAnswersAheadOfALaterGrammarError`). Nothing
 * partial is returned: a call the failure cut short has a half-read argument list, which is a SHORTER list,
 * exactly the shape that makes an operand law look satisfied. The parser never backtracks over a call it
 * recorded, so every one returned is a call A12's parser read too.
 */
internal fun callShapes(text: String, language: ConditionLanguage, entry: SyntaxEntry): List<CallShape> {
    val calls = mutableListOf<CallShape>()
    val parser = ShapeParser(text, language, calls)
    when (entry) {
        SyntaxEntry.CONDITION -> parser.parseCondition()
        SyntaxEntry.OPERATION -> parser.parseOperation()
    }
    return calls
}

/**
 * A recursive-descent reader over the token stream.
 *
 * It reports the FIRST position it cannot use, which is what A12 does too — its parser raises on the first
 * error rather than collecting. Every `parse…` returns a failure or null, so a caller never has to look at
 * the parser's position to know whether it succeeded.
 */
private class ShapeParser(
    /** The text being read, which every recorded call keeps so a consumer can re-read an argument's text. */
    private val source: String,
    language: ConditionLanguage,
    /** Where completed calls are recorded, for [callsIn]; null when only the failure is wanted. */
    private val calls: MutableList<CallShape>? = null,
    /** Where completed comparisons are recorded, for [comparisonsIn]; null when they are not wanted. */
    private val comparisons: MutableList<ComparisonShape>? = null,
) {

    private val tokens: List<Token> = tokenize(source).filter { it.kind != TokenKind.COMMENT }

    private val keywords: Map<String, KeywordKind> = CONDITION_KEYWORDS.getValue(language)

    /** The model language's spelling of the filter clause, the one clause whose operand is a condition. */
    private val filterKeyword: String? = CONDITION_OPERATOR_KEYWORDS.getValue(language)[HAVING]

    /** The model language's spelling of the semantic-index clause, whose operand is a key. */
    private val indexKeyword: String? = CONDITION_OPERATOR_KEYWORDS.getValue(language)[SEMANTIC_INDEX]

    private var index = 0

    /**
     * Whether a bare entity reference may stand where the parser is: an operator's argument, a clause's operand
     * or a field-value bracket, all `entitySpec` positions. An operation and a condition never begin with one
     * ([KERNEL-FINDINGS KF472]), so it is false at the entry rules, inside `{ … }` and in a filter's body.
     */
    private var entitySpecStands = false

    /** How many delimiters are open, so running out of text can be told from meeting the end of it. */
    private var open = 0

    fun parseCondition(): ShapeFailure? = finish(condition())

    fun parseOperation(): ShapeFailure? = finish(expression())

    /** After the entry rule, anything left over is a token the grammar cannot use. */
    private fun finish(failure: ShapeFailure?): ShapeFailure? = failure ?: peek()?.let { unexpected() }

    private fun peek(): Token? = tokens.getOrNull(index)

    private fun kindOf(token: Token): KeywordKind? =
        if (token.kind == TokenKind.NAME) keywords[token.text] else null

    private fun textIs(vararg candidates: String): Boolean {
        val token = peek() ?: return false
        return token.kind == TokenKind.PUNCTUATION && token.text in candidates
    }

    private fun take(): Token = tokens[index++]

    /** The failure for the token under the cursor, or for the end of the text when there is none. */
    private fun unexpected(): ShapeFailure {
        val token = peek()
        return if (token != null) {
            ShapeFailure(token.start, ranOutAwaitingOneToken = false, found = token.text)
        } else {
            ShapeFailure(
                at = tokens.lastOrNull()?.end ?: 0,
                ranOutAwaitingOneToken = open > 0,
                found = "",
            )
        }
    }

    private fun expect(symbol: String): ShapeFailure? {
        if (!textIs(symbol)) {
            return unexpected()
        }
        take()
        return null
    }

    // ── conditions ───────────────────────────────────────────────────────────────────────────────────────

    /** `bedingung`: parts joined by logical operators. Whether the two may be MIXED is a separate family. */
    private fun condition(): ShapeFailure? {
        conditionPart()?.let { return it }
        while (isConnective()) {
            take()
            conditionPart()?.let { return it }
        }
        return null
    }

    private fun isConnective(): Boolean {
        val token = peek() ?: return false
        return token.kind == TokenKind.NAME && CONNECTIVE_SPELLINGS.contains(token.text) &&
            kindOf(token).let { it == KeywordKind.OPERATOR || it == KeywordKind.UNCATALOGED }
    }

    /** `teilBedingung`: a parenthesised condition, a predicate call, or a comparison between expressions. */
    private fun conditionPart(): ShapeFailure? {
        if (textIs("(")) {
            return grouped { condition() }
        }
        val token = peek() ?: return unexpected()
        // A PREDICATE names a production that IS a condition, so it never takes a comparison operator after
        // it. Everything else has to become one, which is what makes a bare reference an error.
        if (token.kind == TokenKind.NAME && kindOf(token).isCallableCondition() && callFollows()) {
            return call()
        }
        if (takesBoundNamePredicate()) {
            return null
        }
        val from = index
        expression()?.let { return it }
        val operatorAt = index
        comparisonOperator()?.let { return it }
        val rightFrom = index
        val failure = expression()
        // A filter body's comparison is recorded like any other: A12 judges it exactly as one outside a
        // filter (`rulekit.validate.laws.FilterBodyComparisonLawsTest`).
        if (failure == null) {
            comparisons?.add(
                ComparisonShape(
                    tokens.subList(from, operatorAt),
                    tokens[operatorAt],
                    tokens.subList(rightFrom, index),
                ),
            )
        }
        return failure
    }

    /**
     * Consumes `CustomCondition <Name>` — the one condition spelled as a keyword plus a BARE NAME.
     *
     * `applikationsBedingung` is the single production of the grammar that is not a call, so a predicate
     * without parentheses is not automatically an error. Leaves the index untouched and answers false
     * wherever the region is some other shape, which is what lets a caller try it ahead of an expression.
     */
    private fun takesBoundNamePredicate(): Boolean {
        val token = peek() ?: return false
        if (token.kind != TokenKind.NAME || !kindOf(token).isCallableCondition() || callFollows()) {
            return false
        }
        val following = tokens.getOrNull(index + 1) ?: return false
        if (following.kind != TokenKind.NAME && following.kind != TokenKind.QUOTED_NAME) {
            return false
        }
        take()
        take()
        return true
    }

    private fun KeywordKind?.isCallableCondition(): Boolean =
        this == KeywordKind.PREDICATE || this == KeywordKind.UNCATALOGED

    private fun callFollows(): Boolean =
        tokens.getOrNull(index + 1).let { it != null && it.kind == TokenKind.PUNCTUATION && it.text == "(" }

    private fun comparisonOperator(): ShapeFailure? {
        val token = peek() ?: return unexpected()
        val symbolic = token.kind == TokenKind.OPERATOR_SYMBOL && token.text in COMPARISON_SYMBOLS
        val worded = token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.OPERATOR &&
            !CONNECTIVE_SPELLINGS.contains(token.text)
        if (!symbolic && !worded) {
            return unexpected()
        }
        take()
        return null
    }

    // ── expressions ──────────────────────────────────────────────────────────────────────────────────────

    /** An operand and the infix operators that may join operands: arithmetic, and the list keywords. */
    private fun expression(): ShapeFailure? {
        term()?.let { return it }
        while (true) {
            val token = peek() ?: return null
            val arithmetic = (token.kind == TokenKind.OPERATOR_SYMBOL && token.text in ARITHMETIC_SYMBOLS) ||
                (token.kind == TokenKind.PUNCTUATION && token.text in PATH_SHAPED_ARITHMETIC)
            // A list keyword joins an argument's operands, never a condition's: inside a filter body it ends the
            // body, and the argument the filtered spec belongs to reads it ([argument]).
            if (!arithmetic && !(entitySpecStands && isListKeyword(token))) {
                return null
            }
            take()
            term()?.let { return it }
        }
    }

    /**
     * One operand.
     *
     * `{ … }` is the grammar's own grouping for an operation (`'{' operation '}'`). A parenthesis groups only
     * a condition (`geklammerteBedingung`), which [conditionPart] opens before it ever reads an operand, so
     * one reaching here is a token the parser cannot use.
     */
    private fun term(): ShapeFailure? {
        val token = peek() ?: return unexpected()
        val bareName = token.kind == TokenKind.QUOTED_NAME || (token.kind == TokenKind.NAME && kindOf(token) == null)
        return when {
            textIs("{") -> within(entitySpecs = false) { grouped("}") { expression() } }
            textIs("[") -> within(entitySpecs = true) { grouped("]") { entitySpecWithClauses() } }
            // A name that is no keyword begins no operation, so it can only be an entity spec.
            bareName && !entitySpecStands -> unexpected()
            // A leading `-` is a sign, not a subtraction, and A12 signs a number literal alone
            // (`commonKonstante : minus='-'? ZiffernFolge …`; `ConditionArithmeticSymbolLawsTest`). The
            // tokenizer files it as punctuation because it is also a path step, so it is matched by text.
            textIs("-") -> { take(); signedNumber() }
            token.kind == TokenKind.NUMBER || token.kind == TokenKind.STRING -> { take(); decimalTail(); null }
            token.kind == TokenKind.NAME || token.kind == TokenKind.QUOTED_NAME -> named()
            // A path may open with its own punctuation: root-anchored, a climb, or the `$` that pins a
            // reference to the outer iteration.
            (textIs("/") || textIs("..") || isOuterMarker()) && entitySpecStands -> entitySpecWithClauses()
            else -> unexpected()
        }
    }

    /**
     * The literal a sign stands on; anything else after it is a token the parser cannot use, and the end of the
     * text is a run-out awaiting that one token, as an open delimiter is.
     */
    private fun signedNumber(): ShapeFailure? {
        if (peek() == null) {
            return ShapeFailure(at = tokens.last().end, ranOutAwaitingOneToken = true, found = "")
        }
        if (peek()?.kind != TokenKind.NUMBER) {
            return unexpected()
        }
        take()
        decimalTail()
        return null
    }

    /** Whether a number with a fractional part stands next. */
    private fun opensADecimal(): Boolean = peek()?.kind == TokenKind.NUMBER &&
        tokens.getOrNull(index + 1)?.text == "." && tokens.getOrNull(index + 2)?.kind == TokenKind.NUMBER

    /** A number's fractional part arrives as `.` then digits, since `.` is also a name separator. */
    private fun decimalTail() {
        if (textIs(".") && tokens.getOrNull(index + 1)?.kind == TokenKind.NUMBER) {
            take()
            take()
        }
    }

    /**
     * A name: a call when a parenthesis follows, a constant on its own, otherwise an entity reference.
     *
     * A PREDICATE's call is a condition, and no operand position takes one, so it stops the parse here. An
     * uncataloged keyword's call is read as a call, because the catalog cannot say which of the two it is.
     */
    private fun named(): ShapeFailure? {
        val token = peek() ?: return unexpected()
        if (token.kind == TokenKind.NAME && callFollows() && kindOf(token) != null) {
            return if (kindOf(token) == KeywordKind.PREDICATE) unexpected() else call()
        }
        if (token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.CONSTANT) {
            take()
            return null
        }
        return entityReference()
    }

    private fun call(): ShapeFailure? {
        val operator = take()
        val operands = mutableListOf<List<Token>>()
        val failure = grouped { arguments(operands, admitsNone = operator.text in EMPTY_ARGUMENT_OPERATORS) }
        // Recorded only on success, so a caller never reads an argument list the parser abandoned half-way.
        if (failure == null) {
            calls?.add(CallShape(operator.text, operator.start, tokens[index - 1].end, operands, source))
        }
        return failure
    }

    /**
     * The arguments between a call's parentheses, at least one unless [admitsNone].
     *
     * An empty list is a token the parser cannot use everywhere but the time constructor: of the grammar's
     * productions only `timeKonstrukt` makes its whole argument list optional (`Zeit '(' (…)? ')'`, and `Time()`
     * is midnight, [KERNEL-FINDINGS] KF78), so `FieldFilled()` is `MVK_UNEXPECTED_TOKEN`
     * at the `)` (`adapter.laws.CheckerNearMissSweepTest`).
     */
    private fun arguments(operands: MutableList<List<Token>>?, admitsNone: Boolean): ShapeFailure? =
        within(entitySpecs = true) {
        if (textIs(")")) {
            return@within if (admitsNone) null else unexpected()
        }
        argument(operands)?.let { return@within it }
        while (textIs(",")) {
            take()
            argument(operands)?.let { return@within it }
        }
        null
    }

    /** One argument, with the token run it spanned handed to [operands] once it parsed. */
    private fun argument(operands: MutableList<List<Token>>?): ShapeFailure? {
        val from = index
        val failure = argument()
        if (failure == null) {
            operands?.add(tokens.subList(from, index))
        }
        return failure
    }

    /**
     * One argument: an expression, possibly carrying the clauses an entity spec may take.
     *
     * Never a condition. Every operator's argument is an `entitySpec`, an `entityListe`, an `operation` or a
     * constant in A12's grammar, so a connective, a comparison or a predicate call inside one is a token the
     * parser cannot use (`rulekit.validate.laws.WhereAConditionMayStandLawsTest`). The one condition an
     * argument can hold is a filter's body, which [clauses] reads.
     *
     * A list keyword may follow the clause chain too, not only a bare operand: in
     * `AtLeastOneDateRangeOverlaps(Claims/Memo For "X" Having [Claims/ClaimRef] == "a" In Code)` the filtered
     * spec is read to its end and the value list after it, since A12 names that spec's own
     * `MVK_WILDCARDS_REQUIRED` — a class it raises once the whole spec is read — and no grammar class
     * (`adapter.laws.CheckerKeyedOperandAgreementTest`, "date-range value list").
     */
    private fun argument(): ShapeFailure? {
        expression()?.let { return it }
        clauses()?.let { return it }
        val token = peek() ?: return null
        if (!isListKeyword(token)) {
            return null
        }
        take()
        return argument()
    }

    /** Whether [token] is `In` or `To`, which join an operand to a value list, in a spelling the lexer admits. */
    private fun isListKeyword(token: Token): Boolean =
        token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.UNCATALOGED && LIST_KEYWORDS.contains(token.text)

    /**
     * The clause chain an entity spec may carry, wherever it is reached from.
     *
     * Shared because the two callers reach the same grammar production from different heads — an argument
     * parses an expression first, a spec a reference — and the clause loop itself is the SAME one. It had
     * been written out twice, which is how the bare-name-predicate permission came to need applying in two
     * places at once.
     */
    private fun clauses(): ShapeFailure? {
        while (true) {
            val token = peek() ?: return null
            if (token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.PATH_OP) {
                take()
                // Only a filter's operand is a condition (`'Mit' bedingung`), and a condition may be the
                // keyword-plus-bare-name predicate, which A12 parses there and refuses from the filter's own
                // last gate. A key is an `entitySpec` or a string, so it is read as an operand.
                if (token.text == filterKeyword) {
                    within(entitySpecs = false) { condition() }?.let { return it }
                } else {
                    // A key is an entity spec or a string (`semanticIndex: entitySpec | StringLiteral`), and a
                    // number is neither unless it is a bare digit sequence, which the grammar reads as an entity
                    // name: `For 5.5` is `MVK_UNEXPECTED_TOKEN` where `For 5` is `MVK_INVALID_ENTITY`
                    // ([KERNEL-FINDINGS KF479]).
                    if (token.text == indexKeyword && opensADecimal()) {
                        return unexpected()
                    }
                    within(entitySpecs = true) { expression() }?.let { return it }
                }
                continue
            }
            if (isScopeMarker()) {
                take()
                take()
                within(entitySpecs = true) { expression() }?.let { return it }
                continue
            }
            return null
        }
    }

    // ── entity references ────────────────────────────────────────────────────────────────────────────────

    /**
     * A reference and the clauses it may carry: a semantic index, a filter, a scope.
     *
     * The clause keywords are the catalog's `PATH_OP` kind, which is exactly the set the grammar attaches to
     * an `entitySpec`. `@From` is the one that does not arrive as a single token — the tokenizer files `@` as
     * punctuation, because it is also the `@*` marker — so it is recognized as the pair.
     */
    private fun entitySpecWithClauses(): ShapeFailure? {
        entityReference()?.let { return it }
        return clauses()
    }

    /** `@From` / `@Von`, which reaches here as `@` then the name. */
    private fun isScopeMarker(): Boolean {
        val at = peek() ?: return false
        if (at.text != "@" || at.kind != TokenKind.PUNCTUATION) {
            return false
        }
        val name = tokens.getOrNull(index + 1) ?: return false
        return name.kind == TokenKind.NAME && keywords["@" + name.text] == KeywordKind.PATH_OP
    }

    /** The `$` that pins a reference to the outer iteration; punctuation or symbol by tokenizer accident. */
    private fun isOuterMarker(): Boolean = peek()?.text == "$"


    /**
     * A path as A12's `entity` production spells it: an optional `$`, a first step (root-anchored by a leading
     * `/`, or a climb), then further steps each after exactly ONE `/` ([entityRunAt], the walk the reference
     * scanner shares). Every departure from that is a token the parser cannot use: a `/` with no step after
     * it, two steps with no `/` between them, a `/` doubled (`adapter.laws.CheckerNearMissSweepTest`).
     */
    private fun entityReference(): ShapeFailure? {
        val run = entityRunAt(tokens, index, ::startsSegment)
        index = run.end
        if (!run.closes) {
            return unexpected()
        }
        categoryTail()
        return null
    }

    /**
     * Whether a path segment starts at [token].
     *
     * A NAME the keyword table knows is a DIFFERENT TERMINAL from a name, in A12's lexer as here
     * (`setTypeInLexer` gives a keyword its own type and everything else `NameOhnePunktSTRAmAnfang`), so a
     * keyword is never a path segment — which is what stops a greedy walk from swallowing the `Having` or the
     * `In` that ends the path. A quoted name is the escape for the collision and always a segment. The one
     * keyword that IS an entity is the rule-context one, which the grammar gives its own production.
     */
    private fun startsSegment(token: Token): Boolean = when (token.kind) {
        TokenKind.QUOTED_NAME, TokenKind.NUMBER -> true
        TokenKind.NAME -> kindOf(token) == null || RULE_CONTEXT_SPELLINGS.contains(token.text)
        else -> false
    }

    /** `X -> Category`, the H-model category selector, which rides an ordinary reference. */
    private fun categoryTail() {
        if (textIs("->")) {
            take()
            if (peek()?.kind == TokenKind.NAME || peek()?.kind == TokenKind.QUOTED_NAME) {
                take()
            }
        }
    }

    // ── delimiters ───────────────────────────────────────────────────────────────────────────────────────

    /** Runs [body] with [entitySpecStands] set to [entitySpecs], restoring it afterwards. */
    private inline fun within(entitySpecs: Boolean, body: () -> ShapeFailure?): ShapeFailure? {
        val outer = entitySpecStands
        entitySpecStands = entitySpecs
        val failure = body()
        entitySpecStands = outer
        return failure
    }

    /** Run [body] inside a delimiter pair, tracking the depth that decides which code a failure draws. */
    private fun grouped(closing: String = ")", body: () -> ShapeFailure?): ShapeFailure? {
        take()
        open++
        body()?.let { return it }
        val failure = expect(closing)
        open--
        return failure
    }

    private companion object {
        /** Both languages' spellings, in every case the lexer admits: the parser is not the one judging them. */
        val CONNECTIVE_SPELLINGS = setOf(
            "And", "AND", "and", "Or", "OR", "or",
            "Und", "UND", "und", "Oder", "ODER", "oder",
        )

        val COMPARISON_SYMBOLS = setOf("==", "!=", "<", ">", "<=", ">=")

        val ARITHMETIC_SYMBOLS = setOf("+", "*", "^")

        /** `-` and `/` are filed as punctuation because each is also a path shape. */
        val PATH_SHAPED_ARITHMETIC = setOf("-", "/")

        /** The operators whose whole argument list is optional: the time constructor, in both languages. */
        val EMPTY_ARGUMENT_OPERATORS = setOf("Zeit", "Time")

        /** `In` and `To` join an operand to a value list; both languages, every case the lexer admits. */
        val LIST_KEYWORDS = setOf("In", "IN", "in", "To", "TO", "to", "Zu", "ZU", "zu")
    }
}

/**
 * The rule-context entity, the one keyword that is a REFERENCE rather than an operator.
 *
 * `regelKontextEntity` is its own production and the table files it as uncataloged, because the operator
 * catalog does not carry it. The parser needs it so `GroupFilled(RuleGroup)` is not refused; a consumer needs
 * it because the spelling names the declaring element's own group, which is a reach no path comparison sees.
 * File-level rather than parser-local so both read one set (see [namesRuleContext]).
 */
internal val RULE_CONTEXT_SPELLINGS = setOf("RuleGroup", "RegelKontext")
