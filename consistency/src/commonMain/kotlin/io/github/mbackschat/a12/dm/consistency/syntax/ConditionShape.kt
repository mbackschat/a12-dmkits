package io.github.mbackschat.a12.dm.consistency.syntax

/** The two syntax entries differ in what may stand at the top level. */
enum class SyntaxEntry { CONDITION, OPERATION }

/** First demanded recognition failure, or null when the complete text parses. */
fun shapeFailure(text: String, language: ConditionLanguage, entry: SyntaxEntry): ShapeFailure? =
    ConditionRecognizer(text, language).recognize(entry).failure

/** The same recognition verdict, restricted to syntax that precedes the supplied lexical failure. */
fun shapeFailureAhead(text: String, language: ConditionLanguage, entry: SyntaxEntry,
    lexical: LexicalFailure): ShapeFailure? = shapeFailure(text, language, entry)
    ?.takeIf { it.kind != SyntaxFailureKind.LEXICAL && it.at <= lexical.at }

/** Offending source slice; EOF has an empty slice at the end of the input (KF493). */
data class ShapeFailure(
    val at: Int,
    val ranOutAwaitingOneToken: Boolean,
    val found: String,
    val kind: SyntaxFailureKind = if (ranOutAwaitingOneToken) SyntaxFailureKind.MATCH else SyntaxFailureKind.DECISION,
    val end: Int = at + found.length,
) {
    val code: String get() = when (kind) {
        SyntaxFailureKind.LEXICAL -> "MVK_LEXER_STANDARD_ERROR"
        SyntaxFailureKind.MATCH -> if (found.isEmpty()) "MVK_EXPECTED_TOKEN_NOT_FOUND" else "MVK_UNEXPECTED_TOKEN"
        SyntaxFailureKind.DECISION -> if (found.isEmpty()) "MVK_INCOMPLETE_INPUT" else "MVK_UNEXPECTED_TOKEN"
    }
}

/** Completed calls retain raw operand tokens so existing consumers own operand interpretation. */
internal data class CallShape(
    val operator: String,
    val at: Int,
    val end: Int,
    val operands: List<List<Token>>,
    val source: String,
)

internal fun CallShape.textOf(operand: List<Token>): String =
    if (operand.isEmpty()) "" else source.substring(operand.first().start, operand.last().end)

/** Both operands completed; the semantic families still decide their types and compatibility. */
internal data class ComparisonShape(val left: List<Token>, val operator: Token, val right: List<Token>)

internal fun comparisonShapes(text: String, language: ConditionLanguage, entry: SyntaxEntry): List<ComparisonShape> =
    ConditionRecognizer(text, language).recognize(entry).comparisons

internal fun callShapes(text: String, language: ConditionLanguage, entry: SyntaxEntry): List<CallShape> =
    ConditionRecognizer(text, language).recognize(entry).calls

/** The model language's keyword table decides whether either spelling is a keyword at a use site. */
internal val RULE_CONTEXT_SPELLINGS = setOf("RuleGroup", "RegelKontext")
