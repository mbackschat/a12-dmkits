package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether DSL text ends on something that still needs a right-hand side — A12's `MVK_INCOMPLETE_INPUT`.
 *
 * A12 reports this code when it runs out of input, which measurably happens for a trailing infix operator of
 * the catalog's `OPERATOR` kind, any of the eleven symbolic comparison and arithmetic operators, and a
 * trailing comma ([KERNEL-FINDINGS KF300]) — provided every delimiter is closed, which is the whole of the
 * restriction below.
 *
 * **It fires only where the text is delimiter-BALANCED, and that restriction replaces a refuted mechanism.**
 * This check originally keyed on the operator catalog's `OPERATOR`/`PATH_OP` split, on the reading that a
 * trailing `PATH_OP` draws `MVK_UNEXPECTED_TOKEN` while a trailing `OPERATOR` draws `MVK_INCOMPLETE_INPUT`.
 * That was measured on one table member and is false in both directions: `FieldFilled(Tier For` — the same
 * keyword with one bracket removed — draws `MVK_INCOMPLETE_INPUT`, and `FieldFilled(Tier And` draws
 * `MVK_UNEXPECTED_TOKEN`. What both carry is an unclosed delimiter EARLIER in the text, so the last token
 * cannot decide the code at all and no grammar-free rule can.
 *
 * So this is deliberately not a mechanism but a boundary around the measured region: every row A12 answers
 * with `MVK_INCOMPLETE_INPUT` for a trailing operator has every delimiter closed, and every row carrying an
 * unclosed one is answered differently. Outside that region the family stays silent, which under-reports
 * broken text — the only safe direction, and the report's gap already says the grammar is not covered.
 */
fun incompleteInput(text: String, language: ConditionLanguage): IncompleteInput? {
    val keywords = CONDITION_KEYWORDS.getValue(language)
    // Whitespace never becomes a token, and a comment is not input the grammar consumes — so the last
    // SIGNIFICANT token is what A12 is left holding, and `… And ;; note` ends on `And` just as `… And ` does.
    val significant = tokenize(text).filter { it.kind != TokenKind.COMMENT }
    val last = significant.lastOrNull() ?: return null
    val dangling = when (last.kind) {
        TokenKind.NAME -> keywords.classifies(last.text, KeywordKind.OPERATOR)
                && significant.everyDelimiterClosed()
        TokenKind.OPERATOR_SYMBOL -> significant.everyDelimiterClosed()
        // A trailing comma is measured on its own and is never balanced by nature — it can only sit inside an
        // argument list that is still open, so requiring closure here would delete the case rather than scope it.
        // `-` and `/` are punctuation rather than operator symbols because each is also a path shape, and that
        // classification silently excluded them from a family whose own doc claimed every arithmetic symbol.
        // Both are measured `MVK_INCOMPLETE_INPUT` when they end balanced text, so they are named here
        // explicitly; `->` and `..` stay out, being structure with no measured row.
        TokenKind.PUNCTUATION ->
            last.text == "," || (last.text in PATH_AMBIGUOUS_OPERATORS && significant.everyDelimiterClosed())
        else -> false
    }
    return if (dangling) IncompleteInput(last.text, last.start) else null
}

/**
 * The two symbols the tokenizer files as punctuation because each is also part of a path — a repetition step
 * followed by a member name, or a `..` climb — and which are arithmetic operators everywhere else.
 */
private val PATH_AMBIGUOUS_OPERATORS = setOf("-", "/")

/**
 * Whether the table classifies [text] as [kind], in any CASE the lexer resolves.
 *
 * A12's lexer admits three spellings of each connective — `And`, `AND`, `and` — and answers a text ending on
 * any of them with the same `MVK_INCOMPLETE_INPUT` (measured: `rulekit.validate.laws.ConnectiveCaseLawsTest`).
 * The catalog stores one canonical spelling per operator and the rest arrive as `UNCATALOGED`, so keying on
 * the kind alone saw only the canonical one and left the other two unreported.
 *
 * **The membership test is what keeps this from becoming an over-rejection.** A fourth case is not a terminal
 * at all: A12 reads `aNd` as an entity name, which leaves a COMPLETE condition followed by a stray name and
 * draws `MVK_UNEXPECTED_TOKEN` rather than an incompleteness. Folding case without consulting the table would
 * report an unfinished text where A12 reports something else entirely, so the variants are looked up in the
 * measured terminal set instead.
 *
 * **It reaches the connectives and nothing else**, which is an enumeration rather than a hope: of the
 * spellings sharing a case-insensitive form, only `and`/`or` — `und`/`oder` in German — have a member the
 * catalog classifies. `in` and `to` are `UNCATALOGED` in all three cases, so no other family moves.
 */
private fun Map<String, KeywordKind>.classifies(text: String, kind: KeywordKind): Boolean {
    val exact = this[text] ?: return false
    return exact == kind || any { (spelling, classified) ->
        classified == kind && spelling.equals(text, ignoreCase = true)
    }
}

/**
 * Whether every `(` and `[` in these tokens is closed by its own kind.
 *
 * The kinds are matched rather than counted because a counter calls `FieldFilled(Tier] And` balanced, and A12
 * answers the mismatch with `MVK_UNEXPECTED_TOKEN` — so counting would put a row A12 reports differently back
 * inside the region this function exists to draw.
 */
/**
 * Whether [text] closes every delimiter it opens — a NECESSARY condition for the grammar to accept it.
 *
 * Not a syntax check: balanced text can still be refused for a dozen reasons. It earns its place as the
 * cheapest sound test for "this condition is definitely broken", which is what a reader has to know before
 * judging a member A12 stops judging once the condition fails ([MessageParameterReference]).
 */
internal fun delimitersBalanced(text: String): Boolean =
    tokenize(text).filter { it.kind != TokenKind.COMMENT }.everyDelimiterClosed()

private fun List<Token>.everyDelimiterClosed(): Boolean {
    val open = ArrayDeque<String>()
    for (token in this) {
        if (token.kind != TokenKind.PUNCTUATION) continue
        when (token.text) {
            "(", "[" -> open.addLast(token.text)
            ")" -> if (open.removeLastOrNull() != "(") return false
            "]" -> if (open.removeLastOrNull() != "[") return false
        }
    }
    return open.isEmpty()
}

/**
 * The token the text ended on, and where.
 *
 * [spelling] is carried rather than re-derived because the author wrote it in their own condition language,
 * and echoing their spelling back is what makes the finding locatable in a long condition.
 */
data class IncompleteInput(val spelling: String, val at: Int)
