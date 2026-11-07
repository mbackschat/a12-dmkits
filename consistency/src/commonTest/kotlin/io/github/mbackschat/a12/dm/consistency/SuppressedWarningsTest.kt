package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A computation's preamble: its `additionalParserInformation` text, or the legacy `errorCodesToSuppress` list A12
 * folds into `@SuppressWarning(...)`.
 *
 * Every verdict below is the Kernel's, measured on the raw stored shapes by
 * `rulekit.validate.laws.ComputationSuppressionLawsTest` and `ComputationParserInformationLawsTest` (kernel 31.3.1,
 * 2026-11-07).
 */
class SuppressedWarningsTest {

    private fun model(suppress: String?, conditionLanguage: String = "en_US", preamble: String? = null) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelInfo":{"name":"model"},"modelConfig":{"conditionLanguage":{"code":"$conditionLanguage"}},
                      "modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
                          "computationAlternatives":[{"operation":"42.00"}]${
                        suppress?.let { ""","errorCodesToSuppress":$it""" } ?: ""
                    }${preamble?.let { ""","additionalParserInformation":$it""" } ?: ""}}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun codesFor(suppress: String?, conditionLanguage: String = "en_US") =
        model(suppress, conditionLanguage).tracked().suppressedWarningFindings().map { it.code }

    /** The codes for an `additionalParserInformation` of [text], JSON-escaped here, beside the legacy [suppress]. */
    private fun codesForText(text: String, conditionLanguage: String = "en_US", suppress: String? = null) =
        model(suppress, conditionLanguage, JsonPrimitive(text).toString()).tracked().suppressedWarningFindings()
            .map { it.code }

    /** The codes for a list holding the one [entry], which is JSON-escaped here so a row can spell it raw. */
    private fun codesForEntry(entry: String, conditionLanguage: String = "en_US") =
        codesFor(JsonArray(listOf(JsonPrimitive(entry))).toString(), conditionLanguage)

    private val supported = "MVK_INVALID_COMPARE_DEC_PLACES"
    private val enumeration = "MVK_ERROR_INVALID_ENUM_CALCULATION"

    @Test
    fun onlyTheSuppressibleWarningsAreAccepted() {
        val actual = mutableMapOf<String, List<String>>()
        actual["absent — the control"] = codesFor(null)
        actual["empty list"] = codesFor("[]")
        actual["the suppressible code"] = codesFor("""["$supported"]""")
        actual["the enumeration class"] = codesFor("""["$enumeration"]""")
        actual["a real but unsuppressible code"] = codesFor("""["MVK_ERROR_CODE_MISSING"]""")
        actual["a code that does not exist"] = codesFor("""["MVK_TOTALLY_BOGUS"]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["absent — the control"] = emptyList()
        expected["empty list"] = emptyList()
        expected["the suppressible code"] = emptyList()
        expected["the enumeration class"] = emptyList()
        // A12 names the unsuppressible code rather than refusing to parse it — a different verdict from the
        // shape failures below, and the reason the two are not merged into one predicate.
        expected["a real but unsuppressible code"] = listOf("MVK_INVALID_SUPPRESSED_WARNING")
        expected["a code that does not exist"] = listOf("MVK_INVALID_SUPPRESSED_WARNING")
        assertEquals(expected, actual, "what this checker reports for each suppressed-warning code")
    }

    /** The list-shaped laws: a list of codes, each judged as a name wherever it stands. */
    @Test
    fun eachEntryIsJudgedAsACode() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the suppressible code twice"] = codesFor("""["$supported","$supported"]""")
        actual["both suppressible codes"] = codesFor("""["$supported","$enumeration"]""")
        actual["the suppressible code beside a bogus one"] = codesFor("""["$supported","MVK_TOTALLY_BOGUS"]""")
        actual["a bogus code ahead of the suppressible one"] = codesFor("""["MVK_TOTALLY_BOGUS","$supported"]""")
        actual["an empty entry"] = codesFor("""[""]""")
        actual["a blank entry"] = codesFor("""["   "]""")
        actual["a numeric entry"] = codesFor("""[7]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["the suppressible code twice"] = emptyList()
        expected["both suppressible codes"] = emptyList()
        expected["the suppressible code beside a bogus one"] = listOf("MVK_INVALID_SUPPRESSED_WARNING")
        expected["a bogus code ahead of the suppressible one"] = listOf("MVK_INVALID_SUPPRESSED_WARNING")
        expected["an empty entry"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["a blank entry"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["a numeric entry"] = listOf("MVK_UNEXPECTED_TOKEN")
        assertEquals(expected, actual, "the preamble takes a list of code tokens and nothing else")
    }

    /** The stored text itself (`ComputationParserInformationLawsTest.whatA12DoesWithEachPreamble`). */
    @Test
    fun theStoredTextIsJudgedAsAPreamble() {
        val actual = linkedMapOf(
            "the scale gate suppressed" to codesForText("@SuppressWarning($supported)"),
            "empty text" to codesForText(""),
            "blank text" to codesForText("  "),
            "two supported codes" to codesForText("@SuppressWarning($supported,$enumeration)"),
            "spaces inside the directive" to codesForText("@ SuppressWarning ( $supported )"),
            "an unsupported code" to codesForText("@SuppressWarning(MVK_TOTALLY_BOGUS)"),
            "no code" to codesForText("@SuppressWarning()"),
            "a bare code, no directive" to codesForText(supported),
            "the preamble followed by a condition" to codesForText("@SuppressWarning($supported) FieldFilled(Price)"),
            "the German directive in an English model" to codesForText("@UnterdrueckeWarnung($supported)"),
            "the German directive in a German model" to codesForText("@UnterdrueckeWarnung($supported)", "de_DE"),
            "the English directive in a German model" to codesForText("@SuppressWarning($supported)", "de_DE"),
        )
        val expected = linkedMapOf(
            "the scale gate suppressed" to emptyList(),
            "empty text" to emptyList(),
            "blank text" to emptyList(),
            "two supported codes" to emptyList(),
            "spaces inside the directive" to emptyList(),
            "an unsupported code" to listOf("MVK_INVALID_SUPPRESSED_WARNING"),
            "no code" to listOf("MVK_UNEXPECTED_TOKEN"),
            "a bare code, no directive" to listOf("MVK_UNEXPECTED_TOKEN"),
            "the preamble followed by a condition" to listOf("MVK_UNEXPECTED_TOKEN"),
            "the German directive in an English model" to listOf("MVK_UNEXPECTED_TOKEN"),
            "the German directive in a German model" to emptyList(),
            "the English directive in a German model" to listOf("MVK_UNEXPECTED_TOKEN"),
        )
        assertEquals(expected, actual, "what this checker reports for each additionalParserInformation")
    }

    /** The text wins, and the legacy list is folded in only when the text is blank. */
    @Test
    fun theLegacyListIsFoldedOnlyWhenTheTextIsBlank() {
        val bogus = """["MVK_TOTALLY_BOGUS"]"""
        val actual = linkedMapOf(
            "a suppressing text beside a bogus list" to codesForText("@SuppressWarning($supported)", suppress = bogus),
            "blank text beside a bogus list" to codesForText(" ", suppress = bogus),
        )
        val expected = linkedMapOf(
            "a suppressing text beside a bogus list" to emptyList(),
            "blank text beside a bogus list" to listOf("MVK_INVALID_SUPPRESSED_WARNING"),
        )
        assertEquals(expected, actual, "when the legacy list is read")
    }

    /**
     * Which single entry A12 reads as a CODE and which it cannot read as one at all, by lexical shape — the
     * rows `ComputationSuppressionLawsTest.whichSingleEntryA12ReadsAsACode` measures (2026-09-24). The entry
     * is the pragma's one operand, so its class is what A12's lexer and parser make of that text: a name it
     * then compares, a token no name can be, or a character it cannot lex.
     */
    @Test
    fun anEntryIsJudgedAsTheTextThePragmaParses() {
        val rows = linkedMapOf(
            "an identifier without the MVK prefix" to ("Late_Fee" to "MVK_INVALID_SUPPRESSED_WARNING"),
            "the supported name in lower case" to (supported.lowercase() to "MVK_INVALID_SUPPRESSED_WARNING"),
            "letters only" to ("Latefee" to "MVK_INVALID_SUPPRESSED_WARNING"),
            "an identifier with a digit" to ("MVK_CODE7" to "MVK_INVALID_SUPPRESSED_WARNING"),
            "an identifier led by a digit" to ("7_DAYS" to "MVK_UNEXPECTED_TOKEN"),
            "two identifiers split by a space" to ("MVK_LATE FEE" to "MVK_UNEXPECTED_TOKEN"),
            "an identifier with a dot" to ("MVK_LATE.FEE" to "MVK_UNEXPECTED_TOKEN"),
            "the supported name padded with spaces" to (" $supported " to null),
            "the supported name padded with a tab and a line break" to ("\t$supported\n" to null),
            "an identifier led by an underscore" to ("_LATE_FEE" to "MVK_INVALID_SUPPRESSED_WARNING"),
            "an identifier holding a colon" to ("MVK:LATE" to "MVK_INVALID_SUPPRESSED_WARNING"),
            "an identifier holding an umlaut" to ("MVK_GEB\u00dcHR" to "MVK_INVALID_SUPPRESSED_WARNING"),
            "an identifier holding an accented letter" to ("MVK_FRA\u00c9" to "MVK_LEXER_STANDARD_ERROR"),
            "a keyword" to ("And" to "MVK_UNEXPECTED_TOKEN"),
            "an aggregate keyword" to ("Sum" to "MVK_UNEXPECTED_TOKEN"),
            "a quoted name" to ("'MVK_LATE'" to "MVK_UNEXPECTED_TOKEN"),
            "a string literal" to ("\"MVK_LATE\"" to "MVK_UNEXPECTED_TOKEN"),
            "an unclosed string literal" to ("\"MVK_LATE" to "MVK_LEXER_STANDARD_ERROR"),
            "an identifier then a comment" to ("MVK_LATE ;; note" to "MVK_UNEXPECTED_TOKEN"),
            "the supported name then a comment" to ("$supported ;; note" to "MVK_UNEXPECTED_TOKEN"),
            "a lone hash" to ("#LATE" to "MVK_LEXER_STANDARD_ERROR"),
            "the row-iteration keyword" to ("#Zeile" to "MVK_UNEXPECTED_TOKEN"),
            "the supported name padded with a no-break space" to ("\u00a0$supported" to "MVK_LEXER_STANDARD_ERROR"),
            "a digit-led identifier then an unlexable character" to ("7_DAYS \u00c9" to "MVK_UNEXPECTED_TOKEN"),
            "a keyword then an unlexable character" to ("And \u00c9" to "MVK_UNEXPECTED_TOKEN"),
        )
        val actual = rows.mapValues { (_, row) -> codesForEntry(row.first) }
        val expected = rows.mapValues { (_, row) -> listOfNotNull(row.second) }
        assertEquals(expected, actual, "which single entry this checker reads as a code")
    }

    /**
     * A keyword is a keyword in the MODEL's condition language only — A12 renders the pragma in that language
     * and retypes a name against its terminals alone (`aKeywordIsJudgedInTheModelsConditionLanguage`).
     */
    @Test
    fun aKeywordIsJudgedInTheModelsConditionLanguage() {
        val actual = linkedMapOf(
            "the supported code, German model" to codesForEntry(supported, "de_DE"),
            "a code that does not exist, German model" to codesForEntry("MVK_TOTALLY_BOGUS", "de_DE"),
            "Summe, English model" to codesForEntry("Summe", "en_US"),
            "Summe, German model" to codesForEntry("Summe", "de_DE"),
            "Sum, English model" to codesForEntry("Sum", "en_US"),
            "Sum, German model" to codesForEntry("Sum", "de_DE"),
        )
        val expected = linkedMapOf(
            "the supported code, German model" to emptyList(),
            "a code that does not exist, German model" to listOf("MVK_INVALID_SUPPRESSED_WARNING"),
            "Summe, English model" to listOf("MVK_INVALID_SUPPRESSED_WARNING"),
            "Summe, German model" to listOf("MVK_UNEXPECTED_TOKEN"),
            "Sum, English model" to listOf("MVK_UNEXPECTED_TOKEN"),
            "Sum, German model" to listOf("MVK_INVALID_SUPPRESSED_WARNING"),
        )
        assertEquals(expected, actual, "a keyword entry, per condition language")
    }

    /** Two entries fail on whichever comes first in the rendered text, an unlexable entry ahead of a later check. */
    @Test
    fun aSecondEntryAnswersWithTheFirstFailureInTheText() {
        val unlexable = "MVK_FRA\u00c9"
        val actual = linkedMapOf(
            "the first unlexable" to codesFor(JsonArray(listOf(JsonPrimitive(unlexable), JsonPrimitive(supported))).toString()),
            "the second unlexable" to codesFor(JsonArray(listOf(JsonPrimitive(supported), JsonPrimitive(unlexable))).toString()),
        )
        val expected = linkedMapOf(
            "the first unlexable" to listOf("MVK_LEXER_STANDARD_ERROR"),
            "the second unlexable" to listOf("MVK_LEXER_STANDARD_ERROR"),
        )
        assertEquals(expected, actual, "two entries, one of them unlexable")
    }

    @Test
    fun aMemberShapeA12sReaderRefusesIsReportedAsUnbuildable() {
        assertEquals(emptyList(), codesFor("null"), "an explicit null list is absence")
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codesFor("""    "$supported" """.trim()), "a bare string")
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codesFor("""{"code":"$supported"}"""), "an object")
        assertEquals(emptyList(), model(null, preamble = "null").tracked().suppressedWarningFindings(), "a null text")
        assertEquals(listOf("MVK_UNEXPECTED_TOKEN"), model(null, preamble = "7").tracked().suppressedWarningFindings()
            .map { it.code }, "a number is read as its text")
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE),
            model(null, preamble = """["@SuppressWarning($supported)"]""").tracked().suppressedWarningFindings()
                .map { it.code }, "a list of text")
    }

    /** The finding names the computation, so a consumer can act on it without re-deriving the path. */
    @Test
    fun theFindingNamesTheComputationThatCarriesTheMember() {
        val finding = model("""["MVK_TOTALLY_BOGUS"]""").tracked().suppressedWarningFindings().single()
        assertEquals("/Catalog/PriceCalc", finding.at)
        assertEquals(
            true,
            finding.explain.contains("MVK_TOTALLY_BOGUS"),
            "the text must name the offending code: ${finding.explain}",
        )
    }
}
