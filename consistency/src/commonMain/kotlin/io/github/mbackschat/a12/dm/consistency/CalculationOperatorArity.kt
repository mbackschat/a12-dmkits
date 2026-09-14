package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * How many divisions and powers one arithmetic expression may carry, and what an exponent has to be.
 *
 * `CheckOperationImpl.check` runs three gates over every operation context: the type walk, which carries the
 * exponent's own demand, then at most ONE division per multiplicative chain, then no power directly under a
 * power ([KERNEL-FINDINGS KF378], `rulekit.validate.laws.CalculationOperatorArityLawsTest`).
 *
 * | the expression | class |
 * |---|---|
 * | an exponent whose declared scale is not zero | `MVK_INVALID_NUMBER_FOR_EXP` |
 * | a second `/` in one chain, with no `+` or `-` between | `MVK_TOO_MANY_DIV_FOR_CALC` |
 * | a second `^` with no lower-precedence operator between | `MVK_TOO_MANY_POW_FOR_CALC` |
 *
 * **Braces reset both counters, and that is the whole reason these shapes reach a checker at all.**
 * `DslRenderer` brackets a left power child and a left division under division, so the typed builders cannot
 * author any of them; every row is stored DM-JSON. The kernel's own comment says the bracketed division
 * COULD be refused and is admitted because the previous parser admitted it — so the brace rule is a
 * compatibility decision rather than a semantic one, and reading it off the class names would get it wrong.
 *
 * **The verdict follows the TEXT, not the source order of the gates.** `check` runs per operation context as
 * the expression is parsed, so an expression violating both arity gates draws whichever violation is written
 * first — measured in both orders, because one order alone reads as "the power gate wins". Inside ONE
 * context the type walk still comes first, so an exponent's own defect pre-empts a nesting the same
 * expression also has.
 *
 * **Silent wherever the scale cannot be read here**: an exponent that is a braced expression, a call, or a
 * field declaring no `maxFractionalDigits`. A12 refuses an undeterminable scale rather than admitting it, so
 * each of those is an under-report — the safe direction, and the one this reader can defend.
 *
 * **Silent across any boundary it does not understand.** A name, a comparison, a connective and a comma all
 * end the current chain, which under-counts a chain that spans one of them and never over-counts.
 */
fun PreparedModel.calculationOperatorArityFindings(): List<Finding> = elements.mapNotNull { element ->
    val body = element.body ?: return@mapNotNull null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
        arityDefects(tokenize(text), group, element)
            .minByOrNull { it.at }
            ?.let { defect -> arityFinding(defect, element, member) }
    }
}

private data class ArityDefect(val code: String, val at: Int, val wrote: String)

private const val INVALID_EXPONENT = "MVK_INVALID_NUMBER_FOR_EXP"

private const val TOO_MANY_POW = "MVK_TOO_MANY_POW_FOR_CALC"

private const val TOO_MANY_DIV = "MVK_TOO_MANY_DIV_FOR_CALC"

/** One level of the expression, with each delimited run recursed into as its own operation context. */
private fun PreparedModel.arityDefects(
    tokens: List<Token>,
    group: String,
    element: DeclaredElement,
): List<ArityDefect> {
    val terms = termsOf(tokens)
    val defects = mutableListOf<ArityDefect>()
    var divisions = 0
    var powers = 0
    terms.forEachIndexed { position, term ->
        when (term) {
            is Term.Operand -> {
                if (term.nested != null) {
                    defects += arityDefects(term.nested, group, element)
                }
                if (term.boundary) {
                    divisions = 0
                    powers = 0
                }
            }
            is Term.Op -> when (term.symbol) {
                "+", "-" -> { divisions = 0; powers = 0 }
                "*" -> powers = 0
                "/" -> {
                    powers = 0
                    divisions++
                    if (divisions > 1) {
                        defects += ArityDefect(TOO_MANY_DIV, term.at, "/")
                    }
                }
                "^" -> {
                    exponentDefect(terms.getOrNull(position + 1), term.at, group, element)
                        ?.let { defects += it }
                    powers++
                    if (powers > 1) {
                        defects += ArityDefect(TOO_MANY_POW, term.at, "^")
                    }
                }
            }
        }
    }
    return defects
}

/**
 * Whether the operand after a `^` declares a whole-number scale.
 *
 * A12 reads the exponent's DECLARED scale rather than the value it could take, so a field an author only
 * ever fills with whole numbers is refused too — measured, and the reason this reads the declaration.
 */
private fun PreparedModel.exponentDefect(
    term: Term?,
    at: Int,
    group: String,
    element: DeclaredElement,
): ArityDefect? {
    val operand = term as? Term.Operand ?: return null
    val digits = when {
        operand.digits != null -> operand.digits
        operand.path != null -> declaredScaleOf(operand.path, group, element) ?: return null
        else -> return null
    }
    return if (digits == 0) null else ArityDefect(INVALID_EXPONENT, at, operand.path ?: "$digits digits")
}

/** The `maxFractionalDigits` a referenced number field declares, or null for every other shape. */
private fun PreparedModel.declaredScaleOf(
    path: String,
    group: String,
    element: DeclaredElement,
): Int? {
    val resolved = resolveEntity(group, path, element.origin) as? Reference.Resolved ?: return null
    val field = elements.firstOrNull { it.path == resolved.path && it.declaredKind == DeclaredKind.FIELD }
    val type = (field?.body?.get("fieldType") as? JsonObject) ?: return null
    val number = (type["NumberType"] as? JsonObject) ?: return null
    return (number["maxFractionalDigits"] as? JsonPrimitive)?.intOrNull
}

private sealed interface Term {

    /** One arithmetic operator at this level. */
    data class Op(val symbol: String, val at: Int) : Term

    /**
     * One operand, or a boundary this reader does not carry a chain across.
     *
     * @param digits the fractional digits a NUMBER literal spells.
     * @param path the entity a `[…]` reference names, in normalized spelling.
     * @param nested the tokens of a braced or parenthesized run, which A12 checks as its own context.
     * @param boundary whether it ends the current chain — a name, a comparison, a comma or a connective.
     */
    data class Operand(
        val at: Int,
        val digits: Int? = null,
        val path: String? = null,
        val nested: List<Token>? = null,
        val boundary: Boolean = false,
    ) : Term
}

private val ARITHMETIC = setOf("+", "-", "*", "/", "^")

/** Splits one token run into this level's operators and operands, delimited runs kept whole. */
private fun termsOf(tokens: List<Token>): List<Term> {
    val terms = mutableListOf<Term>()
    var index = 0
    while (index < tokens.size) {
        val token = tokens[index]
        val closing = CLOSING_OF[token.text]
        when {
            closing != null -> {
                val end = matchingDelimiter(tokens, index, token.text, closing)
                // An unbalanced delimiter is the grammar family's; stopping here leaves every chain this
                // level had already opened unreported, which is the safe direction on a text that will not
                // parse anyway.
                if (end == null) {
                    return terms
                }
                terms += if (token.text == "[") {
                    Term.Operand(token.start, path = normalizedPath(tokens.subList(index + 1, end)))
                } else {
                    Term.Operand(token.start, nested = tokens.subList(index + 1, end))
                }
                index = end + 1
            }
            token.kind == TokenKind.NUMBER -> {
                val fraction = tokens.getOrNull(index + 1)?.takeIf { it.text == "." }
                    ?.let { tokens.getOrNull(index + 2) }?.takeIf { it.kind == TokenKind.NUMBER }
                terms += Term.Operand(token.start, digits = fraction?.text?.length ?: 0)
                index += if (fraction == null) 1 else 3
            }
            token.text in ARITHMETIC -> {
                terms += Term.Op(token.text, token.start)
                index++
            }
            else -> {
                terms += Term.Operand(token.start, boundary = true)
                index++
            }
        }
    }
    return terms
}

private val CLOSING_OF = mapOf("[" to "]", "{" to "}", "(" to ")")

/** The index of [opening]'s partner, or null when the run does not balance. */
private fun matchingDelimiter(tokens: List<Token>, from: Int, opening: String, closing: String): Int? {
    var depth = 0
    (from until tokens.size).forEach { at ->
        when (tokens[at].text) {
            opening -> depth++
            closing -> {
                depth--
                if (depth == 0) {
                    return at
                }
            }
        }
    }
    return null
}

private fun arityFinding(defect: ArityDefect, element: DeclaredElement, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    when (defect.code) {
        INVALID_EXPONENT ->
            "in `$member`: the exponent `${defect.wrote}` is not a whole number — A12 reads an exponent's " +
                "DECLARED scale, so give it a constant with no decimals or a field declaring none"
        TOO_MANY_DIV ->
            "in `$member`: a second division in one chain — A12 admits one per multiplicative chain, so " +
                "brace the inner quotient or split the expression at a `+` or `-`"
        else ->
            "in `$member`: a power directly under a power — A12 admits one, so brace the inner power"
    },
    element.path,
)
