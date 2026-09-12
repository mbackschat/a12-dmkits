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
 * **What decides the code is where the parser stopped**, which is the one thing [KF300]'s two withdrawn
 * grammar-free rules could not know and a parser does:
 *
 * | the parser… | code |
 * |---|---|
 * | met a token it cannot use where it stands — including the end of the text with every delimiter closed | `MVK_UNEXPECTED_TOKEN` |
 * | ran out of text with a delimiter still open | `MVK_EXPECTED_TOKEN_NOT_FOUND` |
 *
 * Both halves are measured. `FieldFilled(Tier` is the only shape of the delimiter grid that runs out with
 * something open, and it is the only one A12 answers with `MVK_EXPECTED_TOKEN_NOT_FOUND`; the other four meet
 * a wrong token first and are all `MVK_UNEXPECTED_TOKEN`. A bare `Tier` reaches the end with nothing open and
 * is `MVK_UNEXPECTED_TOKEN` too, which is why the end of the text is not by itself a "ran out".
 *
 * The earlier phases own their own shapes: text that cannot be tokenized is [lexicalFailure]'s, and text that
 * ends on a dangling operator is [incompleteInput]'s. A caller runs them first.
 */
fun conditionShapeFailure(text: String, language: ConditionLanguage): ShapeFailure? =
    ShapeParser(tokenize(text).filter { it.kind != TokenKind.COMMENT }, CONDITION_KEYWORDS.getValue(language))
        .parseCondition()

/**
 * Whether text forms an OPERATION — a computation's `operation`, which A12 parses with its own entry rule.
 *
 * The two entry rules differ in what they accept at the top: a condition must be a condition, while an
 * operation is an expression. Everything below the entry is shared.
 */
fun operationShapeFailure(text: String, language: ConditionLanguage): ShapeFailure? =
    ShapeParser(tokenize(text).filter { it.kind != TokenKind.COMMENT }, CONDITION_KEYWORDS.getValue(language))
        .parseOperation()

/**
 * Where the parser stopped and what it was holding.
 *
 * @param at the offset of the token it could not use, or the end of the text when it ran out.
 * @param ranOutWithDelimiterOpen the half that picks the code — see the table on [conditionShapeFailure].
 * @param found the token's text, or an empty string at the end of the text, so a finding can quote it.
 */
data class ShapeFailure(val at: Int, val ranOutWithDelimiterOpen: Boolean, val found: String)

/**
 * A recursive-descent reader over the token stream.
 *
 * It reports the FIRST position it cannot use, which is what A12 does too — its parser raises on the first
 * error rather than collecting. Every `parse…` returns a failure or null, so a caller never has to look at
 * the parser's position to know whether it succeeded.
 */
private class ShapeParser(private val tokens: List<Token>, private val keywords: Map<String, KeywordKind>) {

    private var index = 0

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
            ShapeFailure(token.start, ranOutWithDelimiterOpen = false, found = token.text)
        } else {
            ShapeFailure(
                at = tokens.lastOrNull()?.end ?: 0,
                ranOutWithDelimiterOpen = open > 0,
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
        if (token.kind == TokenKind.NAME && kindOf(token).isCallableCondition()) {
            if (callFollows()) {
                return call()
            }
            // `applikationsBedingung` is the one condition that is a keyword followed by a bare name rather
            // than a call, so a predicate without parentheses is not automatically an error.
            val following = tokens.getOrNull(index + 1)
            if (following != null && (following.kind == TokenKind.NAME || following.kind == TokenKind.QUOTED_NAME)) {
                take()
                take()
                return null
            }
        }
        expression()?.let { return it }
        comparisonOperator()?.let { return it }
        return expression()
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
            val listKeyword = token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.UNCATALOGED &&
                LIST_KEYWORDS.contains(token.text)
            if (!arithmetic && !listKeyword) {
                return null
            }
            take()
            term()?.let { return it }
        }
    }

    /**
     * One operand.
     *
     * `{ … }` is the grammar's own grouping for an operation (`'{' operation '}'`), distinct from the
     * parentheses that group a condition, and both are accepted here because an argument can be either.
     */
    private fun term(): ShapeFailure? {
        val token = peek() ?: return unexpected()
        return when {
            textIs("(") -> grouped { expressionOrCondition() }
            textIs("{") -> grouped("}") { expressionOrCondition() }
            textIs("[") -> grouped("]") { entitySpecWithClauses() }
            // A leading `-` is a sign, not a subtraction. The tokenizer files it as punctuation because it is
            // also a path step, so it is matched by text.
            textIs("-") -> { take(); term() }
            token.kind == TokenKind.NUMBER || token.kind == TokenKind.STRING -> { take(); decimalTail(); null }
            token.kind == TokenKind.NAME || token.kind == TokenKind.QUOTED_NAME -> named()
            // A path may open with its own punctuation: root-anchored, a climb, or the `$` that pins a
            // reference to the outer iteration.
            textIs("/") || textIs("..") || isOuterMarker() -> entitySpecWithClauses()
            else -> unexpected()
        }
    }

    /** A number's fractional part arrives as `.` then digits, since `.` is also a name separator. */
    private fun decimalTail() {
        if (textIs(".") && tokens.getOrNull(index + 1)?.kind == TokenKind.NUMBER) {
            take()
            take()
        }
    }

    /** A name: a call when a parenthesis follows, a constant on its own, otherwise an entity reference. */
    private fun named(): ShapeFailure? {
        val token = peek() ?: return unexpected()
        if (token.kind == TokenKind.NAME && callFollows() && kindOf(token) != null) {
            return call()
        }
        if (token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.CONSTANT) {
            take()
            return null
        }
        return entityReference()
    }

    private fun call(): ShapeFailure? {
        take()
        return grouped { arguments() }
    }

    /** An empty argument list is legal for at least one operator (`Zeit()`), so it is not required here. */
    private fun arguments(): ShapeFailure? {
        if (textIs(")")) {
            return null
        }
        argument()?.let { return it }
        while (textIs(",")) {
            take()
            argument()?.let { return it }
        }
        return null
    }

    /**
     * One argument: an expression, possibly carrying the clauses an entity spec may take.
     *
     * A `Having` filter's operand is a whole condition, which is why this loops back into the condition
     * layer rather than staying inside the expression one.
     */
    private fun argument(): ShapeFailure? {
        expressionOrCondition()?.let { return it }
        while (true) {
            val token = peek() ?: return null
            if (token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.PATH_OP) {
                take()
                expressionOrCondition()?.let { return it }
                continue
            }
            if (isScopeMarker()) {
                take()
                take()
                expressionOrCondition()?.let { return it }
                continue
            }
            return null
        }
    }

    /**
     * Whatever the region holds, without deciding which it is first.
     *
     * An argument may be a plain expression, a comparison, or a condition joined by connectives — and only
     * the tokens after it say which. Parsing the expression and then consuming a comparison and any
     * connectives keeps the reader from having to look ahead for something no bounded lookahead can see.
     */
    private fun expressionOrCondition(): ShapeFailure? {
        val token = peek() ?: return unexpected()
        if (token.kind == TokenKind.NAME && kindOf(token).isCallableCondition() && callFollows()) {
            call()?.let { return it }
        } else {
            expression()?.let { return it }
        }
        while (true) {
            if (isConnective()) {
                take()
                expressionOrCondition()?.let { return it }
                continue
            }
            val next = peek() ?: return null
            val symbolic = next.kind == TokenKind.OPERATOR_SYMBOL && next.text in COMPARISON_SYMBOLS
            val worded = next.kind == TokenKind.NAME && kindOf(next) == KeywordKind.OPERATOR &&
                !CONNECTIVE_SPELLINGS.contains(next.text)
            if (!symbolic && !worded) {
                return null
            }
            take()
            expression()?.let { return it }
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
        while (true) {
            val token = peek() ?: return null
            if (token.kind == TokenKind.NAME && kindOf(token) == KeywordKind.PATH_OP) {
                take()
                expressionOrCondition()?.let { return it }
                continue
            }
            if (isScopeMarker()) {
                take()
                take()
                expressionOrCondition()?.let { return it }
                continue
            }
            return null
        }
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
     * A path: an optional `$` marker, then segments joined by `/`, each optionally starred.
     *
     * Written permissively on purpose. The grammar separates a climb (`../`), a root-anchored path and a
     * relative one into their own productions with their own checks; what they share is this shape, and the
     * checks they do not share are about what a path RESOLVES to, which the reference family already owns.
     */
    private fun entityReference(): ShapeFailure? {
        if (isOuterMarker()) {
            take()
        }
        var sawStep = false
        while (true) {
            when {
                // `/` and `..` are both steps AND separators here. The grammar splits them across
                // `entity`, `relativeUp`, `relUp` and `oneUp`, each with its own check about what the walk
                // RESOLVES to — which is the reference family's question, not this one's. What they share is
                // this shape, so one permissive loop reads all four and nothing about resolution is claimed.
                textIs("/") || textIs("..") -> { take(); sawStep = true }
                startsSegment() -> { segment()?.let { return it }; sawStep = true }
                else -> break
            }
        }
        if (!sawStep) {
            return unexpected()
        }
        categoryTail()
        return null
    }

    /**
     * Whether a path segment starts here.
     *
     * A NAME the keyword table knows is a DIFFERENT TERMINAL from a name, in A12's lexer as here
     * (`setTypeInLexer` gives a keyword its own type and everything else `NameOhnePunktSTRAmAnfang`), so a
     * keyword is never a path segment — which is what stops a greedy walk from swallowing the `Having` or the
     * `In` that ends the path. A quoted name is the escape for the collision and always a segment. The one
     * keyword that IS an entity is the rule-context one, which the grammar gives its own production.
     */
    private fun startsSegment(): Boolean {
        val token = peek() ?: return false
        return when (token.kind) {
            TokenKind.QUOTED_NAME, TokenKind.NUMBER -> true
            TokenKind.NAME -> kindOf(token) == null || RULE_CONTEXT_SPELLINGS.contains(token.text)
            else -> false
        }
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

    /** One path step: a name, its dotted sub-names, and the `*` and `@*` markers a repetition may carry. */
    private fun segment(): ShapeFailure? {
        val token = peek() ?: return unexpected()
        when (token.kind) {
            TokenKind.NAME, TokenKind.QUOTED_NAME, TokenKind.NUMBER -> take()
            else -> return unexpected()
        }
        while (textIs(".")) {
            val dot = index
            take()
            val next = peek()
            if (next != null && (next.kind == TokenKind.NAME || next.kind == TokenKind.QUOTED_NAME ||
                    next.kind == TokenKind.NUMBER)
            ) {
                take()
            } else if (next != null && next.kind == TokenKind.OPERATOR_SYMBOL && next.text == "*") {
                take()
            } else {
                index = dot
                break
            }
        }
        if (peek()?.kind == TokenKind.OPERATOR_SYMBOL && peek()?.text == "*") {
            take()
        }
        return null
    }

    // ── delimiters ───────────────────────────────────────────────────────────────────────────────────────

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

        /**
         * The rule-context entity, the one keyword that is a REFERENCE rather than an operator.
         *
         * `regelKontextEntity` is its own production and the table files it as uncataloged, because the
         * operator catalog does not carry it. Without this it would be excluded from a path along with the
         * genuine keywords and `GroupFilled(RuleGroup)` would be refused.
         */
        val RULE_CONTEXT_SPELLINGS = setOf("RuleGroup", "RegelKontext")

        /** `In` and `To` join an operand to a value list; both languages, every case the lexer admits. */
        val LIST_KEYWORDS = setOf("In", "IN", "in", "To", "TO", "to", "Zu", "ZU", "zu")
    }
}
