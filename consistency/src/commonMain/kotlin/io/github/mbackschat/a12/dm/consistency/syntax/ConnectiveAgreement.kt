package io.github.mbackschat.a12.dm.consistency.syntax

/**
 * Whether `And` and `Or` are mixed inside ONE bracket pair — A12's `MVK_BRACKET_MISSING`.
 *
 * A12 refuses to guess a precedence between the two connectives rather than applying one silently, and it
 * does so from a semantic ACTION on the `bedingung` context (`CheckBedingungImpl.check` compares the context's
 * first connective with the current one). That is what makes this the one phase reportable without a grammar
 * after two others were withdrawn: the two withdrawn rules asked which CODE a broken text draws, which depends
 * on where the parser stands, while this one asks a question about a region the token stream already delimits.
 *
 * **A level is a bracket PAIR, not a depth**, and the difference is measured rather than reasoned
 * ([KERNEL-FINDINGS KF301]): two filters one parenthesis deep in DIFFERENT calls carry different connectives
 * and are ADMITTED, so a depth counter would reject text A12 takes. Three productions open a `bedingung` — the
 * entry rule, `geklammerteBedingung: '(' bedingung ')'`, and the entity filter's `'Mit' filterCond=bedingung`
 * — and the filter is the one with no delimiter of its own, so it gets its own frame here, ended by the comma
 * or the closing delimiter that ends it in the grammar.
 *
 * **Where it stays silent, all of it under-reporting.** A connective spelled in a case the keyword table does
 * not carry (`AND`, `and`, `ODER`) is not seen at all, so a mix involving one is missed; and a frame this
 * opens can only be FINER than A12's, never coarser, which is the safe direction — a finer frame misses a
 * mix, a coarser one would invent one.
 */
fun connectiveDisagreement(text: String, language: ConditionLanguage): ConnectiveDisagreement? {
    val connectives = CONNECTIVES.getValue(language)
    val frames = ArrayDeque<Frame>()
    frames.addLast(Frame(filter = false))
    for (token in tokenize(text)) {
        when (token.kind) {
            TokenKind.NAME -> {
                val connective = connectives[token.text]
                if (connective != null) {
                    val frame = frames.last()
                    val first = frame.connective
                    if (first == null) {
                        frame.connective = connective
                        frame.spelling = token.text
                    } else if (first != connective) {
                        return ConnectiveDisagreement(frame.spelling!!, token.text, token.start)
                    }
                } else if (FILTER_KEYWORDS[language] == token.text) {
                    // The filter's condition is a `bedingung` of its own and carries no delimiter, so nothing
                    // else would ever close a frame opened here — hence [Frame.filter].
                    frames.addLast(Frame(filter = true))
                }
            }

            TokenKind.PUNCTUATION -> when (token.text) {
                "(", "[" -> frames.addLast(Frame(filter = false))
                ")", "]" -> {
                    frames.closeFilters()
                    if (frames.size > 1) frames.removeLast()
                }
            }

            else -> Unit
        }
    }
    return null
}

/**
 * Pop every filter frame at the top, which the enclosing closing delimiter ends.
 *
 * The grammar ends a filter's condition at the argument boundary as well, so a comma could close one too —
 * and it is deliberately not handled, because the only text where the two differ puts a bare connective after
 * a comma, which is no argument A12's grammar accepts. A branch no legal input reaches is a branch no test
 * can justify.
 */
private fun ArrayDeque<Frame>.closeFilters() {
    while (size > 1 && last().filter) removeLast()
}

/** One `bedingung` context: the connective it committed to, and how it ends. */
private class Frame(val filter: Boolean) {
    var connective: Connective? = null
    var spelling: String? = null
}

private enum class Connective { AND, OR }

/**
 * Every spelling of each connective the lexer resolves, DERIVED from the generated keyword table.
 *
 * Only the canonical spelling per language is named here, because WHICH names are connectives is the one fact
 * the table cannot supply: it carries a [KeywordKind] and nothing finer, and `OPERATOR` covers `PatternMatched`
 * and `DiffersWithToleranceRange1` as well — so it says a name is an operator but not that it JOINS two
 * conditions. `ConnectiveAgreementTest` pins each spelling against the table, so a regeneration that renamed
 * one fails rather than silently switching this family off.
 *
 * A12 admits three cases per connective — `And`, `AND`, `and` — and a mix written in any of them draws the
 * same `MVK_BRACKET_MISSING`. Listing them here by hand would be a second live statement of a set
 * `consistency/build.gradle` already declares and `ConditionKeywordProjectionTest` already measures against
 * the Kernel's own terminal bundle, so the canonical spelling is named once and the rest are looked up.
 *
 * **The intersection with the table is load-bearing, not a formality.** A bare case-insensitive comparison
 * would also match `aNd`, which the lexer does NOT resolve — there it is an ordinary field name, and calling
 * it a connective would invent a rejection A12 never makes. Restricting to spellings the table carries keeps
 * the match inside the measured terminal set.
 */
private val CONNECTIVES: Map<ConditionLanguage, Map<String, Connective>> = mapOf(
    ConditionLanguage.EN to spellingsOf(ConditionLanguage.EN, "And" to Connective.AND, "Or" to Connective.OR),
    ConditionLanguage.DE to spellingsOf(ConditionLanguage.DE, "Und" to Connective.AND, "Oder" to Connective.OR),
)

private fun spellingsOf(
    language: ConditionLanguage,
    vararg canonical: Pair<String, Connective>,
): Map<String, Connective> {
    val terminals = CONDITION_KEYWORDS.getValue(language).keys
    return canonical.flatMap { (spelling, connective) ->
        terminals.filter { it.equals(spelling, ignoreCase = true) }.map { it to connective }
    }.toMap()
}

/** The entity filter keyword, the third production that opens a `bedingung`. */
private val FILTER_KEYWORDS: Map<ConditionLanguage, String> = mapOf(
    ConditionLanguage.EN to "Having",
    ConditionLanguage.DE to "Mit",
)

/**
 * The two connectives found at one level, and where the SECOND one sits.
 *
 * Both spellings are carried because the author wrote them in their own condition language, and because the
 * fix is to bracket one of the two — naming only the offset would leave them counting parentheses.
 */
data class ConnectiveDisagreement(val first: String, val second: String, val at: Int)
