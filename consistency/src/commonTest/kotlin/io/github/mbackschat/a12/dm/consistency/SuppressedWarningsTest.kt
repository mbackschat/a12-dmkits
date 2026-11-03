package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A computation's `errorCodesToSuppress`, which A12 renders into one `@SuppressWarning(...)` pragma.
 *
 * Every verdict below is the Kernel's, measured on the raw stored shapes by
 * `rulekit.validate.laws.ComputationSuppressionLawsTest` (kernel 30.8.1, 2026-09-12). The member is a LIST
 * that can carry at most one entry, so the rows that matter most are the ones separating "this entry is
 * wrong" from "there is more than one entry" — different A12 codes, and a checker reading the member as a
 * set would report neither.
 */
class SuppressedWarningsTest {

    private fun model(suppress: String?, conditionLanguage: String = "en_US") = prepare(
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
                    }}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun codesFor(suppress: String?, conditionLanguage: String = "en_US") =
        model(suppress, conditionLanguage).tracked().suppressedWarningFindings().map { it.code }

    /** The codes for a list holding the one [entry], which is JSON-escaped here so a row can spell it raw. */
    private fun codesForEntry(entry: String, conditionLanguage: String = "en_US") =
        codesFor(JsonArray(listOf(JsonPrimitive(entry))).toString(), conditionLanguage)

    private val supported = "MVK_INVALID_COMPARE_DEC_PLACES"

    @Test
    fun onlyTheOneSuppressibleWarningIsAccepted() {
        val actual = mutableMapOf<String, List<String>>()
        actual["absent — the control"] = codesFor(null)
        actual["empty list"] = codesFor("[]")
        actual["the suppressible code"] = codesFor("""["$supported"]""")
        actual["a real but unsuppressible code"] = codesFor("""["MVK_ERROR_CODE_MISSING"]""")
        actual["a code that does not exist"] = codesFor("""["MVK_TOTALLY_BOGUS"]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["absent — the control"] = emptyList()
        expected["empty list"] = emptyList()
        expected["the suppressible code"] = emptyList()
        // A12 names the unsuppressible code rather than refusing to parse it — a different verdict from the
        // shape failures below, and the reason the two are not merged into one predicate.
        expected["a real but unsuppressible code"] = listOf("MVK_INVALID_SUPPRESSED_WARNING")
        expected["a code that does not exist"] = listOf("MVK_INVALID_SUPPRESSED_WARNING")
        assertEquals(expected, actual, "what this checker reports for each suppressed-warning code")
    }

    /**
     * The list-shaped laws, which exist only because A12 renders the member into text.
     *
     * The two-entry row carries the SUPPORTED code twice on purpose: it is the control proving the finding is
     * about the comma rather than about an invalid entry, exactly as the Kernel rows measure it.
     */
    @Test
    fun aSecondEntryCannotParseWhateverItSpells() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the suppressible code twice"] = codesFor("""["$supported","$supported"]""")
        actual["the suppressible code beside a bogus one"] = codesFor("""["$supported","MVK_TOTALLY_BOGUS"]""")
        actual["an empty entry"] = codesFor("""[""]""")
        actual["a blank entry"] = codesFor("""["   "]""")
        actual["a numeric entry"] = codesFor("""[7]""")

        val expected = mutableMapOf<String, List<String>>()
        // Two VALID codes fail exactly as one valid plus one bogus does, so validity explains nothing here.
        expected["the suppressible code twice"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["the suppressible code beside a bogus one"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["an empty entry"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["a blank entry"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["a numeric entry"] = listOf("MVK_UNEXPECTED_TOKEN")
        assertEquals(expected, actual, "the pragma takes one code token and nothing else")
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

    /** Two entries fail on whichever comes first in the rendered text: the first entry's lexing, or the comma. */
    @Test
    fun aSecondEntryAnswersWithTheFirstFailureInTheText() {
        val unlexable = "MVK_FRA\u00c9"
        val actual = linkedMapOf(
            "the first unlexable" to codesFor(JsonArray(listOf(JsonPrimitive(unlexable), JsonPrimitive(supported))).toString()),
            "the second unlexable" to codesFor(JsonArray(listOf(JsonPrimitive(supported), JsonPrimitive(unlexable))).toString()),
        )
        val expected = linkedMapOf(
            "the first unlexable" to listOf("MVK_LEXER_STANDARD_ERROR"),
            "the second unlexable" to listOf("MVK_UNEXPECTED_TOKEN"),
        )
        assertEquals(expected, actual, "two entries, one of them unlexable")
    }

    @Test
    fun aMemberShapeA12sReaderRefusesIsReportedAsUnbuildable() {
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codesFor("null"), "an explicit null")
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codesFor("""    "$supported" """.trim()), "a bare string")
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codesFor("""{"code":"$supported"}"""), "an object")
    }

    /**
     * An explicit `null` is NAMED as one, not lumped in with every other non-array shape.
     *
     * Both land in the same tier so the codes above cannot show the difference, and the difference is what an
     * author acts on: `null` means delete the member, while a wrong container means rewrite it as a list.
     */
    @Test
    fun anExplicitNullIsNamedRatherThanCalledANonArray() {
        assertEquals(
            true,
            model("null").tracked().suppressedWarningFindings().single().explain.contains("an explicit `null`"),
            "an explicit null is named as one",
        )
        assertEquals(
            true,
            model("""{"code":"$supported"}""").tracked().suppressedWarningFindings().single()
                .explain.contains("not a JSON array"),
            "a container is named as the wrong shape",
        )
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
