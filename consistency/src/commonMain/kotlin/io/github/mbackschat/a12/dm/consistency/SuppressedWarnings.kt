package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.isAsciiDigit
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computation's parser preamble: its `additionalParserInformation` text, or the legacy `errorCodesToSuppress`
 * list A12 folds into one.
 *
 * **A12 judges TEXT, not a set.** The reader folds the legacy list into `@SuppressWarning(a,b)` (`@UnterdrueckeWarnung`
 * in a German model) when the text is blank, and the text is then parsed on its own as a preamble and nothing else;
 * a preamble that fails stops the computation's other checks, which this family's [DeclarationStage.PREAMBLE] gives
 * (`ComputationParserInformationLawsTest`, `ComputationSuppressionLawsTest`, kernel 31.3.1). Kernel 31.1.1 rendered
 * the list into a one-code pragma, so a second entry was a parse error there.
 *
 * **Which names are suppressible is A12's grammar check**: any name outside [SUPPRESSIBLE_WARNINGS] draws
 * `MVK_INVALID_SUPPRESSED_WARNING`, with the offending code echoed in the message. Which tokens are names is the
 * lexer's question rather than a character class of our own, so [preambleVerdict] asks this checker's tokenizer.
 */
internal fun TrackedFactBase.suppressedWarningFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.COMPUTATION }.flatMap { suppressionFindings(it) }

/** The static scale gate on `==` / `!=` between two numbers, which [OperationAnalysis] reports. */
internal const val SUPPRESSIBLE = "MVK_INVALID_COMPARE_DEC_PLACES"

/** Every name A12's preamble may suppress (`CheckPreambleImpl`, kernel 31.3.1). */
internal val SUPPRESSIBLE_WARNINGS = setOf(SUPPRESSIBLE, "MVK_ERROR_INVALID_ENUM_CALCULATION")

/** The preamble A12 parses for one computation, read from its stored members. */
internal sealed interface ComputationPreamble {
    /** No preamble: both members absent, `null`, empty or blank. */
    data object None : ComputationPreamble

    /** The text A12 parses, either stored or [folded] from the legacy list. */
    data class Text(val text: String, val folded: Boolean) : ComputationPreamble

    /** A member A12's reader cannot bind, named by [member]. */
    data class Unreadable(val member: String, val shape: String) : ComputationPreamble
}

/**
 * The preamble of a computation [body] in a model whose condition language is [language].
 *
 * The legacy list is bound by the reader whatever the text holds, so a list that is not one is unreadable even
 * beside a text; an explicit `null` is absence for either member (kernel 31.3.1; 31.1.1 refused a `null` list).
 */
internal fun computationPreamble(body: JsonObject, language: ConditionLanguage): ComputationPreamble {
    val legacy = body["errorCodesToSuppress"]
    if (legacy != null && legacy !is JsonNull && legacy !is JsonArray) {
        return ComputationPreamble.Unreadable("errorCodesToSuppress", "not a JSON array")
    }
    val stored = body["additionalParserInformation"]
    if (stored != null && stored !is JsonPrimitive) {
        return ComputationPreamble.Unreadable("additionalParserInformation", "not a string")
    }
    (stored as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeUnless { it.isA12Blank() }?.let {
        return ComputationPreamble.Text(it, folded = false)
    }
    val entries = (legacy as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return ComputationPreamble.None
    // An entry that is not a scalar renders as nothing, which A12's grammar refuses where a name belongs.
    val codes = entries.joinToString(",") { (it as? JsonPrimitive)?.content.orEmpty() }
    return ComputationPreamble.Text("@${directive(language)}($codes)", folded = true)
}

/**
 * The warnings a computation [body] suppresses: the names of its preamble when A12 admits it, and none otherwise,
 * since a refused preamble stops the computation's checks before any of them could matter.
 */
internal fun suppressedWarnings(body: JsonObject?, language: ConditionLanguage): Set<String> {
    val preamble = body?.let { computationPreamble(it, language) } as? ComputationPreamble.Text ?: return emptySet()
    return if (preambleVerdict(preamble.text, language) == null) preambleNames(preamble.text) else emptySet()
}

private fun TrackedFactBase.suppressionFindings(element: TrackedDeclaration): List<Finding> {
    // An envelope A12 cannot bind is the alternatives family's finding; naming it again would report one
    // unreadable computation twice in a single report.
    val body = element.body ?: return emptyList()
    val preamble = when (val read = computationPreamble(body, conditionLanguage)) {
        ComputationPreamble.None -> return emptyList()
        is ComputationPreamble.Unreadable -> return listOf(unbuildable(element,
            "`${read.member}` is ${read.shape}, which A12's own reader refuses — omitting the member is what declares " +
                "no suppression"))
        is ComputationPreamble.Text -> read
    }
    val code = preambleVerdict(preamble.text, conditionLanguage) ?: return emptyList()
    val source = if (preamble.folded) "`errorCodesToSuppress` renders into `${preamble.text}`, which" else
        "`additionalParserInformation` `${preamble.text}`"
    val supported = SUPPRESSIBLE_WARNINGS.joinToString(" and ") { "`$it`" }
    val explain = when (code) {
        INVALID_SUPPRESSED_WARNING ->
            "$source names a warning A12 does not let a computation suppress; only $supported can be"
        LEXER_STANDARD_ERROR -> "$source holds a character A12's condition lexer cannot read"
        else -> "$source is not a preamble A12's parser can read: write `@${directive(conditionLanguage)}(` and " +
            "comma-separated codes from $supported, then `)`, and nothing else"
    }
    return listOf(Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, element.path))
}

internal const val UNEXPECTED_TOKEN = "MVK_UNEXPECTED_TOKEN"
private const val INVALID_SUPPRESSED_WARNING = "MVK_INVALID_SUPPRESSED_WARNING"
private const val LEXER_STANDARD_ERROR = "MVK_LEXER_STANDARD_ERROR"

/** The preamble keyword in [language]. */
private fun directive(language: ConditionLanguage): String =
    if (language == ConditionLanguage.EN) "SuppressWarning" else "UnterdrueckeWarnung"

/**
 * What A12 answers the preamble [text] with, or `null` when it admits it.
 *
 * A12 parses `@ <directive> ( name (, name)* )` and nothing after it, and checks the names once the preamble has
 * parsed. So the FIRST token that breaks the shape answers: a character its lexer cannot take is
 * `MVK_LEXER_STANDARD_ERROR` and any other token is `MVK_UNEXPECTED_TOKEN`; then a name outside
 * [SUPPRESSIBLE_WARNINGS] is `MVK_INVALID_SUPPRESSED_WARNING`; then text behind the `)` is one of the first two.
 * Whitespace is discarded, so padding is admitted. Every clause is a row of `ComputationSuppressionLawsTest` or
 * `ComputationParserInformationLawsTest` (kernel 31.3.1), including the order rows: an unlexable entry answers ahead
 * of an unsupported one, and a parse failure ahead of an unlexable character behind it.
 *
 * **A keyword is not a name, and is a keyword only in the model's [language]**, because A12's lexer retypes a name
 * against that language's terminals alone — `Sum` is refused as a token in an English model and as an unsupported
 * code in a German one, and `Summe` the other way round. The directive is likewise the model language's own.
 */
private fun preambleVerdict(text: String, language: ConditionLanguage): String? {
    val tokens = tokenize(text)
    var at = 0
    fun failure(): String = if (tokens.getOrNull(at)?.isUnlexable == true) LEXER_STANDARD_ERROR else UNEXPECTED_TOKEN
    fun expect(matches: (Token) -> Boolean): Boolean {
        val token = tokens.getOrNull(at)
        if (token == null || !matches(token)) return false
        at++
        return true
    }
    if (!expect { it.isPunctuation("@") }) return failure()
    if (!expect { it.kind == TokenKind.NAME && it.text == directive(language) }) return failure()
    if (!expect { it.isPunctuation("(") }) return failure()
    val names = mutableListOf<String>()
    do {
        if (!expect { it.isPreambleName(language) }) return failure()
        names += tokens[at - 1].text
    } while (expect { it.isPunctuation(",") })
    if (!expect { it.isPunctuation(")") }) return failure()
    if (names.any { it !in SUPPRESSIBLE_WARNINGS }) return INVALID_SUPPRESSED_WARNING
    return if (at < tokens.size) failure() else null
}

/** The names a preamble [text] lists, once [preambleVerdict] admitted it. */
private fun preambleNames(text: String): Set<String> =
    tokenize(text).filter { it.kind == TokenKind.NAME }.drop(1).map { it.text }.toSet()

private fun Token.isPunctuation(symbol: String): Boolean = kind == TokenKind.PUNCTUATION && text == symbol

/** Whether A12's lexer fails on this token. */
private val Token.isUnlexable: Boolean
    get() = kind == TokenKind.ILLEGAL_CHARACTER || kind == TokenKind.UNTERMINATED_STRING ||
        kind == TokenKind.UNTERMINATED_QUOTED_NAME

/** A name the preamble takes: the tokenizer's NAME minus a digit-led one and minus a keyword of [language]. */
private fun Token.isPreambleName(language: ConditionLanguage): Boolean =
    kind == TokenKind.NAME && !text.first().isAsciiDigit() && text !in CONDITION_KEYWORDS.getValue(language)

private fun unbuildable(element: TrackedDeclaration, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain. A12 cannot build the model from this, so no consistency verdict about it exists",
    element.path,
)
