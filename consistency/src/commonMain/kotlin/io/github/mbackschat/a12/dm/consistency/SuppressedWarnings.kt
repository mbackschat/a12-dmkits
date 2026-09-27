package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computation's `errorCodesToSuppress` — a stored LIST that A12 can only ever honour one entry of.
 *
 * **It is not a set A12 inspects; it is TEXT A12 renders and then re-parses.**
 * `ComputationAlternativesJoiner.createPreamble` builds `@SuppressWarning(<codes joined by ",">)` and puts it
 * in front of the generated rule condition, so every law here follows from that rendering rather than from
 * any set semantics. Reading the member as a collection is what gets it wrong.
 *
 * **A second entry is a parse error whatever it spells.** The pragma takes one code, so two entries render
 * `@SuppressWarning(a,b)` and fail as `MVK_UNEXPECTED_TOKEN` — measured with the SUPPORTED code carried
 * twice, so nothing about validity can account for it (`ComputationSuppressionLawsTest`, kernel 30.8.1,
 * 2026-09-12). A blank entry renders `@SuppressWarning()` and a numeric one `@SuppressWarning(7)`, both the
 * same failure. This is the one place a plural member is single-valued, and a checker that admitted a list
 * would admit models A12 refuses.
 *
 * **Only `MVK_INVALID_COMPARE_DEC_PLACES` is suppressible**, which the Kernel grammar states and the rows
 * confirm: any other NAME draws `MVK_INVALID_SUPPRESSED_WARNING`, with the offending code echoed in the
 * message. Which entries are names is the lexer's question rather than a character class of our own, so
 * [pragmaVerdict] asks this checker's tokenizer — see there for the rows that made a hand-written test wrong.
 *
 * **An explicit `null` refuses the READER here**, unlike the rounding pair beside it ([KERNEL-FINDINGS
 * KF297]), because the member is bound `@NonNull List<String>` rather than through an `Optional` accessor —
 * a property of the binding, not of DM-JSON, and measured rather than carried across.
 */
internal fun TrackedFactBase.suppressedWarningFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.COMPUTATION }.flatMap { suppressionFindings(it) }

/**
 * The single code A12's `@SuppressWarning` pragma accepts — the static scale gate on `==` / `!=` between two
 * numbers, which [OperationAnalysis] reports and which this family compares an authored code
 * against.
 */
internal const val SUPPRESSIBLE = "MVK_INVALID_COMPARE_DEC_PLACES"

private fun TrackedFactBase.suppressionFindings(element: TrackedDeclaration): List<Finding> {
    // An envelope A12 cannot bind is the alternatives family's finding; naming it again would report one
    // unreadable computation twice in a single report.
    val body = element.body ?: return emptyList()
    val declared = body["errorCodesToSuppress"] ?: return emptyList()
    // `JsonNull` is not a `JsonArray`, so this one test catches it too; the two are told apart in the message
    // below, where the difference is load-bearing — `null` means delete the member, a container means rewrite it.
    if (declared !is JsonArray) {
        return listOf(
            unbuildable(
                element,
                "`errorCodesToSuppress` is ${if (declared is JsonNull) "an explicit `null`" else "not a JSON array"}" +
                    ", which A12's own reader refuses — omitting the member is what declares no suppression",
            ),
        )
    }
    if (declared.isEmpty()) {
        return emptyList()
    }
    // The rendering, not the list, is what A12 judges: everything below reads the pragma this array becomes.
    val spelled = declared.map { (it as? JsonPrimitive)?.content }
    val rendered = spelled.joinToString(",") { it.orEmpty() }
    val code = if (null in spelled) UNEXPECTED_TOKEN else pragmaVerdict(rendered, conditionLanguage)
    val explain = when {
        code == null -> return emptyList()
        declared.size > 1 && code == UNEXPECTED_TOKEN ->
            "this computation suppresses ${declared.size} codes, and A12 renders them into a single " +
                "`@SuppressWarning(...)` pragma that takes exactly one — a second entry fails to parse " +
                "whatever it spells. Keep at most `$SUPPRESSIBLE`."
        code == INVALID_SUPPRESSED_WARNING ->
            "`${rendered.trim()}` is not a warning A12 lets a computation suppress — `$SUPPRESSIBLE` is the only one"
        code == LEXER_STANDARD_ERROR ->
            "`errorCodesToSuppress` renders into `@SuppressWarning($rendered)`, which holds a character A12's " +
                "condition lexer cannot read — the only suppressible code is `$SUPPRESSIBLE`"
        else ->
            "`errorCodesToSuppress` renders into `@SuppressWarning($rendered)`, which A12's condition " +
                "parser cannot read — the entry must be a bare code token, and the only suppressible one " +
                "is `$SUPPRESSIBLE`"
    }
    return listOf(Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, element.path))
}

internal const val UNEXPECTED_TOKEN = "MVK_UNEXPECTED_TOKEN"
private const val INVALID_SUPPRESSED_WARNING = "MVK_INVALID_SUPPRESSED_WARNING"
private const val LEXER_STANDARD_ERROR = "MVK_LEXER_STANDARD_ERROR"

/**
 * What A12 answers the pragma over [text] with, or `null` when it admits it.
 *
 * The operand is one `NameOhnePunktSTRAmAnfang` token, and `CheckPreambleImpl` compares that token's text with
 * the one suppressible name. So A12 answers the FIRST thing in [text] that is not that name: a character its
 * lexer cannot take is `MVK_LEXER_STANDARD_ERROR`, any other token where the name belongs or behind it is
 * `MVK_UNEXPECTED_TOKEN`, and a lone name that is not the suppressible one is `MVK_INVALID_SUPPRESSED_WARNING`.
 * Whitespace around the name is discarded, so a padded suppressible code is admitted. Every clause is a row of
 * `ComputationSuppressionLawsTest` (kernel 30.8.1, 2026-09-24), including the two that fix the order: an
 * unlexable first entry answers ahead of the comma behind it, and a parse failure answers ahead of an unlexable
 * character behind it.
 *
 * **A keyword is not a name, and is a keyword only in the model's [language]**, because A12 renders the pragma
 * in that language and its lexer retypes a name against that language's terminals alone — `Sum` is refused as
 * a token in an English model and as an unsupported code in a German one, and `Summe` the other way round.
 *
 * **This replaced a hand-written character test that got five measured shapes wrong**: it refused the padded
 * suppressible code A12 admits — a false ERROR, the one direction a checker must never take — and named the
 * wrong class for an underscore-led or colon-holding name, an accented letter, and a keyword.
 */
private fun pragmaVerdict(text: String, language: ConditionLanguage): String? {
    val tokens = tokenize(text)
    val first = tokens.firstOrNull() ?: return UNEXPECTED_TOKEN
    val behind = tokens.getOrNull(1)
    return when {
        first.isUnlexable -> LEXER_STANDARD_ERROR
        !first.isPragmaName(language) -> UNEXPECTED_TOKEN
        behind != null -> if (behind.isUnlexable) LEXER_STANDARD_ERROR else UNEXPECTED_TOKEN
        first.text == SUPPRESSIBLE -> null
        else -> INVALID_SUPPRESSED_WARNING
    }
}

/** Whether A12's lexer fails on this token. */
private val Token.isUnlexable: Boolean
    get() = kind == TokenKind.ILLEGAL_CHARACTER || kind == TokenKind.UNTERMINATED_STRING ||
        kind == TokenKind.UNTERMINATED_QUOTED_NAME

/** A name the pragma takes: the tokenizer's NAME minus a digit-led one and minus a keyword of [language]. */
private fun Token.isPragmaName(language: ConditionLanguage): Boolean =
    kind == TokenKind.NAME && !text.first().isDigit() && text !in CONDITION_KEYWORDS.getValue(language)

private fun unbuildable(element: TrackedDeclaration, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain. A12 cannot build the model from this, so no consistency verdict about it exists",
    element.path,
)
