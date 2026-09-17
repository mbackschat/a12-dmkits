package io.github.mbackschat.a12.dm.consistency

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

    private fun model(suppress: String?) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
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

    private fun codesFor(suppress: String?) = model(suppress).tracked().suppressedWarningFindings().map { it.code }

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
