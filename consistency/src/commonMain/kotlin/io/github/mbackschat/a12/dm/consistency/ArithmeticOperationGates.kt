package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.KeywordKind
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Everything `CheckOperationImpl.check` asks of one arithmetic expression, in the order it asks it.
 *
 * The method runs three gates over every operation context — the TYPE walk, then at most one division per
 * multiplicative chain, then no power directly under a power ([KERNEL-FINDINGS KF378] and [KF396],
 * locked by `rulekit.validate.laws.CalculationOperatorArityLawsTest` and
 * `rulekit.validate.laws.ArithmeticOperandKindLawsTest`):
 *
 * | the expression | class |
 * |---|---|
 * | `*`, `/`, `-` or `^` over anything but a number | `MVK_NO_NUMBER_FOR_CALC` |
 * | `+` over a mixed pair, or over an enumeration carrying display labels | `MVK_NO_STRING_OR_NUMBER_FOR_ADD` |
 * | an exponent whose declared scale is not zero | `MVK_INVALID_NUMBER_FOR_EXP` |
 * | a second `/` in one chain, with no `+` or `-` between | `MVK_TOO_MANY_DIV_FOR_CALC` |
 * | a second `^` with no lower-precedence operator between | `MVK_TOO_MANY_POW_FOR_CALC` |
 *
 * **One family rather than two, because the TYPE walk PRE-EMPTS both arity gates inside one context.** A
 * string in a two-division chain is `MVK_NO_NUMBER_FOR_CALC` alone, and the same chain with a number in that
 * position is the division class — measured in both directions. Two families reporting these independently
 * would name a second class on a model A12 answers once, so the walk suppresses this level's arity defects
 * wherever its kind pass found one.
 *
 * **`+` is not an arithmetic operator here at all.** It never asks the number question: it concatenates two
 * string-LIKE sides, adds two number-like ones, and refuses every mixed pair with a class of its own. A
 * custom-typed field is string-like, the operation-type map sorting `IStringType` and `IPredefinedType` into
 * one base type.
 *
 * **…and `+` refuses an enumeration that carries display LABELS**, under that same class, while a labelless
 * enumeration of the same tokens is admitted. A category access is exempt, which is A12's own note at that
 * branch — this reader cannot reduce a category spelling to a declaration and so stays silent on it.
 *
 * **Braces reset both arity counters, and that is the whole reason those shapes reach a checker at all.**
 * `DslRenderer` brackets a left power child and a left division under division, so the typed builders cannot
 * author any of them; every arity row is stored DM-JSON. The kernel's own comment says the bracketed
 * division COULD be refused and is admitted because the previous parser admitted it — so the brace rule is a
 * compatibility decision rather than a semantic one, and reading it off the class names would get it wrong.
 *
 * **The verdict follows the TEXT across contexts, not the source order of the gates.** `check` runs per
 * operation context as the expression is parsed, so an expression violating two gates in different contexts
 * draws whichever violation is written first — measured in both orders, because one order alone reads as
 * "the power gate wins".
 *
 * **Silent wherever a type cannot be read here**: a typedef or unspecified declaration, a written-out text, a
 * call, a comma-separated argument list, and a field declaring no `maxFractionalDigits` under `^`. A12
 * refuses an undeterminable exponent scale rather than admitting it, so each of those is an under-report —
 * the safe direction, and the one this reader can defend.
 *
 * **Silent across any boundary it does not understand.** A name, a comparison, a connective and a comma all
 * end the current chain, which under-counts a chain that spans one of them and never over-counts.
 */
internal fun TrackedFactBase.arithmeticOperationGateFindings(): List<Finding> = declarations().mapNotNull { element ->
    val body = element.body ?: return@mapNotNull null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
        walkOperation(tokenize(text), text, group, element).defects
            .minByOrNull { it.at }
            ?.let { defect -> operationFinding(defect, element, member) }
    }
}

/** One gate a level fails: where it is written, and [end], where A12 raises it — the end of its chain. */
private data class OperationDefect(val code: String, val at: Int, val wrote: String, val end: Int)

/** A gate one level's walk finds, before the level knows where the chain holding it ends. */
private data class Violation(val code: String, val at: Int, val wrote: String)

private const val INVALID_EXPONENT = "MVK_INVALID_NUMBER_FOR_EXP"

private const val TOO_MANY_POW = "MVK_TOO_MANY_POW_FOR_CALC"

private const val TOO_MANY_DIV = "MVK_TOO_MANY_DIV_FOR_CALC"

private const val NOT_A_NUMBER = "MVK_NO_NUMBER_FOR_CALC"

private const val MIXED_ADD = "MVK_NO_STRING_OR_NUMBER_FOR_ADD"

/**
 * The base types this reader can give an operand, named as A12's `OperationResultBaseType` groups them.
 *
 * [OTHER] is a declaration whose base type is KNOWN and is neither — a date, a range, a boolean, a confirm —
 * so it fails both arms. A null type is a shape this reader does not judge at all, which is a different
 * answer and the reason the two are not collapsed.
 */
private enum class OperandBaseType { NUMBER, TEXT, LABELLED_ENUM, OTHER }

private fun OperandBaseType?.isNumberLike(): Boolean = this == OperandBaseType.NUMBER

private fun OperandBaseType?.isStringLike(): Boolean =
    this == OperandBaseType.TEXT || this == OperandBaseType.LABELLED_ENUM

/** What one operation context answers with, and its result type as the enclosing context sees it. */
private class LevelResult(val type: OperandBaseType?, val defects: List<OperationDefect>)

/**
 * One operation context: its nested contexts first, then its own type walk, then its own arity gates.
 *
 * The arity gates are dropped wherever the type walk answered at THIS level, which is the pre-emption A12's
 * own method order produces and the grid measures.
 */
private fun TrackedFactBase.walkOperation(
    tokens: List<Token>,
    text: String,
    group: String,
    element: TrackedDeclaration,
): LevelResult {
    val terms = termsOf(tokens, text, CONDITION_KEYWORDS.getValue(conditionLanguage))
    val defects = mutableListOf<OperationDefect>()
    val types = terms.map { term ->
        val operand = term as? Term.Operand ?: return@map null
        if (operand.nested == null) {
            operandType(operand, group, element)
        } else {
            walkOperation(operand.nested, text, group, element).also { defects += it.defects }.type
        }
    }
    val kindDefects = mutableListOf<Violation>()
    val resultType = typeWalk(terms, types, group, element, kindDefects)
    defects += kindDefects.ifEmpty { arityDefects(terms) }
        .mapNotNull { violation ->
            terms.closedChainEndAt(violation.at)?.let { OperationDefect(violation.code, violation.at, violation.wrote, it) }
        }
    return LevelResult(resultType, defects)
}

/**
 * A12's `checkAndSetTypes`, which is the multiplicative demand and the additive pairing over one level.
 *
 * @return the level's result type, or null where this reader could not settle it.
 */
private fun TrackedFactBase.typeWalk(
    terms: List<Term>,
    types: List<OperandBaseType?>,
    group: String,
    element: TrackedDeclaration,
    defects: MutableList<Violation>,
): OperandBaseType? {
    multiplicativeDefects(terms, types, group, element, defects)
    return additiveType(terms, types, defects)
}

/** `*`, `/` and `^` demand a number on each side, and `^` then demands a whole-number exponent. */
private fun TrackedFactBase.multiplicativeDefects(
    terms: List<Term>,
    types: List<OperandBaseType?>,
    group: String,
    element: TrackedDeclaration,
    defects: MutableList<Violation>,
) {
    terms.forEachIndexed { position, term ->
        val symbol = (term as? Term.Op)?.symbol ?: return@forEachIndexed
        if (symbol !in MULTIPLICATIVE) {
            return@forEachIndexed
        }
        listOf(position - 1, position + 1).forEach { side ->
            val operand = terms.getOrNull(side) as? Term.Operand ?: return@forEach
            if (types.getOrNull(side) != null && !types[side].isNumberLike()) {
                defects += Violation(NOT_A_NUMBER, operand.at, operand.wrote(symbol))
            }
        }
        if (symbol == "^") {
            exponentDefect(terms.getOrNull(position + 1), types.getOrNull(position + 1), term.at, group, element)
                ?.let { defects += it }
        }
    }
}

private val MULTIPLICATIVE = setOf("*", "/", "^")

/**
 * `+` and `-` over the level's additive segments, folded LEFT as the grammar associates them.
 *
 * Each segment carrying a multiplicative operator is a NUMBER whatever its operands were: A12 types the
 * product before the sum sees it, and any bad operand of that product has already been reported.
 */
private fun additiveType(
    terms: List<Term>,
    types: List<OperandBaseType?>,
    defects: MutableList<Violation>,
): OperandBaseType? {
    val segments = additiveSegments(terms, types)
    if (segments.isEmpty()) {
        return null
    }
    var left: OperandBaseType? = segments.first().type
    var leftAt = segments.first().at
    segments.drop(1).forEach { segment ->
        val right = segment.type
        if (segment.joinedBy == null) {
            left = right
            leftAt = segment.at
            return@forEach
        }
        when (segment.joinedBy) {
            "-" -> {
                if (left != null && !left.isNumberLike()) {
                    defects += Violation(NOT_A_NUMBER, leftAt, "-")
                } else if (right != null && !right.isNumberLike()) {
                    defects += Violation(NOT_A_NUMBER, segment.at, "-")
                }
                left = OperandBaseType.NUMBER
            }
            else -> left = when {
                left.isNumberLike() && right.isNumberLike() -> OperandBaseType.NUMBER
                left.isStringLike() && right.isStringLike() -> {
                    val labelled = if (left == OperandBaseType.LABELLED_ENUM) leftAt else segment.at
                    if (left == OperandBaseType.LABELLED_ENUM || right == OperandBaseType.LABELLED_ENUM) {
                        defects += Violation(MIXED_ADD, labelled, "+")
                    }
                    OperandBaseType.TEXT
                }
                left != null && right != null -> {
                    defects += Violation(MIXED_ADD, segment.at, "+")
                    null
                }
                else -> null
            }
        }
        leftAt = segment.at
    }
    return left
}

/** One additive segment: the operator that joined it to its predecessor, its type, and where it starts. */
private class AdditiveSegment(val joinedBy: String?, val type: OperandBaseType?, val at: Int)

/**
 * The level split at its `+` and `-` operators.
 *
 * A segment is typed only where it is ONE operand or carries a multiplicative operator; anything else — a
 * comma-separated argument list, a run this reader did not reduce — stays null, so the fold declines to
 * judge the pairing rather than guessing at it.
 */
private fun additiveSegments(terms: List<Term>, types: List<OperandBaseType?>): List<AdditiveSegment> {
    val segments = mutableListOf<AdditiveSegment>()
    var joinedBy: String? = null
    var operands = mutableListOf<Pair<Term.Operand, OperandBaseType?>>()
    var multiplicative = false
    // Where a segment holding no operand is written — `+ *` — so a class about it lands on its own chain.
    var firstOperatorAt: Int? = null

    fun close(nextJoin: String?) {
        val at = operands.firstOrNull()?.first?.at ?: firstOperatorAt ?: 0
        val type = when {
            multiplicative -> OperandBaseType.NUMBER
            operands.size == 1 -> operands.single().second
            else -> null
        }
        segments += AdditiveSegment(joinedBy, type, at)
        joinedBy = nextJoin
        operands = mutableListOf()
        multiplicative = false
        firstOperatorAt = null
    }

    terms.forEachIndexed { position, term ->
        when {
            term is Term.Op && term.symbol in ADDITIVE -> close(term.symbol)
            term is Term.Op -> {
                multiplicative = true
                firstOperatorAt = firstOperatorAt ?: term.at
            }
            // A boundary ends the chain rather than joining it: the segment after a comparison, a comma or a
            // connective is a fresh expression, and pairing it with what came before would invent an addition.
            term is Term.Operand && term.boundary -> close(null)
            term is Term.Operand -> operands += term to types.getOrNull(position)
        }
    }
    close(null)
    return segments
}

private val ADDITIVE = setOf("+", "-")

/** The base type A12's operation-type map gives this operand, or null where this reader may not say. */
private fun TrackedFactBase.operandType(
    operand: Term.Operand,
    group: String,
    element: TrackedDeclaration,
): OperandBaseType? {
    if (operand.digits != null) {
        return OperandBaseType.NUMBER
    }
    val field = fieldOf(operand, group, element) ?: return null
    return when (val declared = field.declaredFieldType()) {
        FieldType.NUMBER -> OperandBaseType.NUMBER
        FieldType.STRING, FieldType.CUSTOM_FIELD -> OperandBaseType.TEXT
        FieldType.ENUMERATION ->
            if (field.carriesDisplayLabels()) OperandBaseType.LABELLED_ENUM else OperandBaseType.TEXT
        FieldType.TYPE_DEF, FieldType.UNSPECIFIED, null -> null
        else -> if (declared in DECLARED_OTHER_BASE_TYPES) OperandBaseType.OTHER else null
    }
}

/** The declarations whose base type this reader knows to be neither number-like nor string-like. */
private val DECLARED_OTHER_BASE_TYPES = setOf(FieldType.BOOLEAN, FieldType.CONFIRM, FieldType.DATE_RANGE) +
    TEMPORAL_DECLARED_TYPES

/** Whether any of this enumeration's values carries a display label, which is what `+` refuses. */
private fun TrackedDeclaration.carriesDisplayLabels(): Boolean {
    val values = enumerationBody()?.get("values") as? JsonArray ?: return false
    return values.filterIsInstance<JsonObject>()
        .any { (it["label"] as? JsonArray)?.isNotEmpty() == true }
}

/**
 * Whether the operand after a `^` declares a whole-number scale.
 *
 * A12 reads the exponent's DECLARED scale rather than the value it could take, so a field an author only
 * ever fills with whole numbers is refused too — measured, and the reason this reads the declaration. It is
 * asked after the exponent's KIND, so a non-numeric one never reaches it.
 */
private fun TrackedFactBase.exponentDefect(
    term: Term?,
    type: OperandBaseType?,
    at: Int,
    group: String,
    element: TrackedDeclaration,
): Violation? {
    val operand = term as? Term.Operand ?: return null
    if (type != null && !type.isNumberLike()) {
        return null
    }
    val digits = when {
        operand.digits != null -> operand.digits
        operand.path != null -> declaredScaleOf(operand, group, element) ?: return null
        else -> return null
    }
    return if (digits == 0) null else Violation(INVALID_EXPONENT, at, operand.path ?: "$digits digits")
}

/** A12's `checkOnlyOneDiv` and `checkOnlyOnePow` over one level's chain. */
private fun arityDefects(terms: List<Term>): List<Violation> {
    val defects = mutableListOf<Violation>()
    var divisions = 0
    var powers = 0
    terms.forEach { term ->
        when (term) {
            is Term.Operand -> if (term.boundary) {
                divisions = 0
                powers = 0
            }
            is Term.Op -> when (term.symbol) {
                "+", "-" -> { divisions = 0; powers = 0 }
                "*" -> powers = 0
                "/" -> {
                    powers = 0
                    divisions++
                    if (divisions > 1) {
                        defects += Violation(TOO_MANY_DIV, term.at, "/")
                    }
                }
                "^" -> {
                    powers++
                    if (powers > 1) {
                        defects += Violation(TOO_MANY_POW, term.at, "^")
                    }
                }
            }
        }
    }
    return defects
}

/**
 * The field a `[…]` operand at this level names, or null for every shape this reader does not resolve.
 *
 * A keyed operand is read through [operandReferenceIn], which names its value only where the chain vouches for
 * the key: the operand's normalized path runs the key into the value (`Claims/MemoFor"X"`), so `[Claims/Memo
 * For "X"] + 1` resolved nothing and every gate here stayed silent where A12 types the keyed field as it types
 * the unkeyed one (`adapter.laws.CheckerKeyedOperandAgreementTest`, "arithmetic under a pattern").
 */
private fun TrackedFactBase.fieldOf(
    operand: Term.Operand,
    group: String,
    element: TrackedDeclaration,
): TrackedDeclaration? {
    val written = operand.written ?: return null
    val path = operandReferenceIn(written, group, element) { operand.path?.let { OperandReference(it) } }
        ?.reference ?: return null
    val resolved = resolveEntity(group, path, element.origin) as? Reference.Resolved ?: return null
    return declarationAt(resolved.path, DeclaredKind.FIELD)
}

/** The `maxFractionalDigits` a referenced number field declares, or null for every other shape. */
private fun TrackedFactBase.declaredScaleOf(
    operand: Term.Operand,
    group: String,
    element: TrackedDeclaration,
): Int? {
    val field = fieldOf(operand, group, element) ?: return null
    val type = (field.body?.get("fieldType") as? JsonObject) ?: return null
    val number = (FieldType.NUMBER.bodyIn(type)) ?: return null
    return (number["maxFractionalDigits"] as? JsonPrimitive)?.intOrNull
}

private sealed interface Term {

    val at: Int

    /** Where the term's last token ends. */
    val end: Int

    /** One arithmetic operator at this level. */
    data class Op(val symbol: String, override val at: Int, override val end: Int) : Term

    /**
     * One operand, or a boundary this reader does not carry a chain across.
     *
     * @param digits the fractional digits a NUMBER literal spells.
     * @param path the entity a `[…]` reference names, in normalized spelling.
     * @param written the `[…]` reference as it is written, brackets included, which a key is read off.
     * @param nested the tokens of a braced or parenthesized run, which A12 checks as its own context.
     * @param boundary whether it ends the current chain — a name, a comparison, a comma or a connective.
 * @param mayBeAnOperand whether that boundary can be an operator's operand ([mayBeAnOperand]).
     */
    data class Operand(
        override val at: Int,
        override val end: Int,
        val digits: Int? = null,
        val path: String? = null,
        val written: String? = null,
        val nested: List<Token>? = null,
        val boundary: Boolean = false,
        val mayBeAnOperand: Boolean = false,
    ) : Term
}

/** How a finding names this operand — its own spelling where it has one, else the operator it failed. */
private fun Term.Operand.wrote(symbol: String): String = path ?: "the operand of `$symbol`"

private val ARITHMETIC = setOf("+", "-", "*", "/", "^")

/** Splits one token run of [text] into this level's operators and operands, delimited runs kept whole. */
private fun termsOf(tokens: List<Token>, text: String, keywords: Map<String, KeywordKind>): List<Term> {
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
                    Term.Operand(token.start, tokens[end].end, path = normalizedPath(tokens.subList(index + 1, end)),
                        written = text.substring(token.start, tokens[end].end))
                } else {
                    Term.Operand(token.start, tokens[end].end, nested = tokens.subList(index + 1, end))
                }
                index = end + 1
            }
            token.kind == TokenKind.NUMBER -> {
                val fraction = tokens.getOrNull(index + 1)?.takeIf { it.text == "." }
                    ?.let { tokens.getOrNull(index + 2) }?.takeIf { it.kind == TokenKind.NUMBER }
                val last = if (fraction == null) token else tokens[index + 2]
                terms += Term.Operand(token.start, last.end, digits = fraction?.text?.length ?: 0)
                index += if (fraction == null) 1 else 3
            }
            token.text in ARITHMETIC -> {
                terms += Term.Op(token.text, token.start, token.end)
                index++
            }
            else -> {
                terms += Term.Operand(token.start, token.end, boundary = true,
                    mayBeAnOperand = token.mayBeAnOperand(keywords))
                index++
            }
        }
    }
    return terms
}

/**
 * Whether a token this level cannot read may still be an operator's operand: a string, a quoted name, or a name
 * that is no comparison, connective, clause keyword or predicate. A call's name or a constant is one; a
 * keyword such as `PatternViolated` ends the chain as a comparison would.
 */
private fun Token.mayBeAnOperand(keywords: Map<String, KeywordKind>): Boolean = when (kind) {
    TokenKind.STRING, TokenKind.QUOTED_NAME -> true
    TokenKind.NAME -> keywords[text] !in NOT_AN_OPERAND
    else -> false
}

private val NOT_AN_OPERAND = setOf(KeywordKind.OPERATOR, KeywordKind.PATH_OP, KeywordKind.PREDICATE)

private val CLOSING_OF = mapOf("[" to "]", "{" to "}", "(" to ")")

/**
 * Where the chain holding the term written at [at] ends, which is where A12 raises the chain's gates, or null
 * where the chain never closes.
 *
 * `checkOperation` runs once the operation context is read, so a chain the parser stops inside raises
 * nothing: `[ProductCode] * == "ABC"` is `MVK_UNEXPECTED_TOKEN` at the `==`, never the operand's
 * `MVK_NO_NUMBER_FOR_CALC` ([KERNEL-FINDINGS KF491]'s law, for an operation). Two shapes stop it, and both
 * are read narrowly, since this reader has no keyword table: an operator directly after an operator, unless
 * it is the sign of the number after it (`commonKonstante : minus='-'? ZiffernFolge …`), and an operator that
 * ends the chain where the boundary after it cannot be its operand. A name or a string after it may be one
 * (a call, a constant), so that chain is still taken as closed. A boundary ends a chain, as in [termsOf].
 */
private fun List<Term>.closedChainEndAt(at: Int): Int? {
    var from = 0
    forEachIndexed { position, term ->
        if (term is Term.Operand && term.boundary) {
            if (subList(from, position).any { it.at == at }) {
                return closedEnd(from, position)
            }
            from = position + 1
        }
    }
    check(subList(from, size).any { it.at == at }) { "a violation at $at sits on no term of its level" }
    return closedEnd(from, size)
}

/** The end of the chain `[from, until)`, or null where it does not close; the term at [until] ended it. */
private fun List<Term>.closedEnd(from: Int, until: Int): Int? {
    val chain = subList(from, until)
    chain.forEachIndexed { position, term ->
        if (term is Term.Op && chain.getOrNull(position - 1) is Term.Op && !term.signs(chain.getOrNull(position + 1))) {
            return null
        }
    }
    if (chain.last() is Term.Op && (getOrNull(until) as? Term.Operand)?.mayBeAnOperand != true) {
        return null
    }
    return chain.last().end
}

/** Whether this `-` is the sign of the number literal [next], which is part of that constant. */
private fun Term.Op.signs(next: Term?): Boolean = symbol == "-" && (next as? Term.Operand)?.digits != null

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

private fun operationFinding(
    defect: OperationDefect,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    when (defect.code) {
        NOT_A_NUMBER ->
            "in `$member`: `${defect.wrote}` is not a number, and every arithmetic operator but `+` reads " +
                "two numbers"
        MIXED_ADD ->
            "in `$member`: `+` adds two numbers or joins two texts, and this pair is neither — an " +
                "enumeration carrying display labels is refused on that arm too, even beside a text"
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
    TextSpan(member, defect.at, defect.end),
)
