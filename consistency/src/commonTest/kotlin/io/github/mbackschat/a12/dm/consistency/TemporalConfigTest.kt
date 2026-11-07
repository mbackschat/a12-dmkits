package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF314] — a date field's config against its declared format.
 *
 * Every row mirrors one measured through stored JSON against the real kernel
 * (`rulekit.validate.laws.TemporalConfigWireShapeLawsTest`, whose own grid settles the MEMBER NAMES this
 * family reads, and `TemporalDeclarationConfigAdmissionLawsTest` / `TemporalAdditionalCheckCarrierLawsTest`,
 * which sweep the gate across the whole admitted format vocabulary).
 *
 * **The silent rows are the ones that could do damage**, because this family reports A12's own consistency
 * code on models A12 reads and builds. A `FULL` precision and, since kernel 31.2, the pre-1900 check on any
 * carrier are shapes A12 ACCEPTS — reporting one would refuse a model the kernel takes, and nothing downstream
 * could catch it. A format outside the admitted vocabulary draws neither config code: A12 refuses it with its own sentence
 * instead, which the checker names only over the probed Refused table (KERNEL-FINDINGS KF648).
 */
class TemporalConfigTest {

    /** A model whose single field carries [fieldType] verbatim. */
    private fun codes(fieldType: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Issued","Field":{"fieldType":$fieldType}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private fun date(body: String) = codes("""{"type":"DateType","DateType":$body}""")

    private val partial = listOf("MVK_OPTIONAL_DATE_TYPE_INVALID")
    private val refused = listOf(RK_DATE_FORMAT_INVALID)

    /**
     * The pre-1900 check draws nothing on any of its three carriers since kernel 31.2 ([KERNEL-FINDINGS KF677]).
     * Through 31.1.1 the DATE and FRAGMENT rows were refused on a year-free format; these rows lock that the
     * checker no longer reports what A12 now admits.
     */
    @Test
    fun thePre1900CheckIsReportedOnNoCarrier() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a DATE with a year-free format"] = date("""{"format":"MM","youngerThan1900Check":true}""")
        actual["a DATE with the trailing-dot format"] = date("""{"format":"dd.MM.","youngerThan1900Check":true}""")
        actual["a FRAGMENT with a year-free format"] = codes(
            """{"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"MM",""" +
                """"youngerThan1900Check":true}}""",
        )
        actual["a RANGE with a year-free pair"] = codes(
            """{"type":"DateRangeType","DateRangeType":{"format":"MM","rangeSeparator":"/",""" +
                """"youngerThan1900Check":true}}""",
        )
        // A refused format still draws its own sentence (KERNEL-FINDINGS KF648), check or not.
        actual["the check on a format A12 refuses"] = date("""{"format":"MM.yyyy","youngerThan1900Check":true}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a DATE with a year-free format"] = emptyList()
        expected["a DATE with the trailing-dot format"] = emptyList()
        expected["a FRAGMENT with a year-free format"] = emptyList()
        expected["a RANGE with a year-free pair"] = emptyList()
        expected["the check on a format A12 refuses"] = refused
        assertEquals(expected, actual, "the pre-1900 check gates nothing")
    }

    @Test
    fun aPartialPrecisionIsReportedOnAnythingButACompletePureDate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["DAY_OPTIONAL on a complete date — the control"] =
            date("""{"format":"yyyy-MM-dd","datePrecision":"DAY_OPTIONAL"}""")
        for (precision in listOf("DAY_OPTIONAL", "MONTH_OPTIONAL", "YEAR_OPTIONAL")) {
            actual["$precision on an incomplete format"] =
                date("""{"format":"MM","datePrecision":"$precision"}""")
        }
        // The row that says the gate is not "carries all three components": the instant does, and is refused.
        actual["DAY_OPTIONAL on the instant format"] =
            date("""{"format":"yyyy-MM-dd'T'HH:mm:ss","datePrecision":"DAY_OPTIONAL"}""")
        actual["the FULL default written out"] = date("""{"format":"MM","datePrecision":"FULL"}""")
        actual["a precision with no format, so the default applies"] =
            date("""{"datePrecision":"DAY_OPTIONAL"}""")
        // The row the vocabulary restriction exists for, and the only gate it is load-bearing on: this one
        // reports every format that is not a complete pure date, so without the restriction an unrecognised
        // format would draw a finding on the strength of a probe's limit. Measured as a surviving mutant.
        actual["a precision on a format A12 refuses"] =
            date("""{"format":"MM.yyyy","datePrecision":"DAY_OPTIONAL"}""")
        actual["a precision on a format outside the probed universe"] =
            date("""{"format":"QQQ","datePrecision":"DAY_OPTIONAL"}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["DAY_OPTIONAL on a complete date — the control"] = emptyList()
        for (precision in listOf("DAY_OPTIONAL", "MONTH_OPTIONAL", "YEAR_OPTIONAL")) {
            expected["$precision on an incomplete format"] = partial
        }
        expected["DAY_OPTIONAL on the instant format"] = partial
        expected["the FULL default written out"] = emptyList()
        expected["a precision with no format, so the default applies"] = emptyList()
        expected["a precision on a format A12 refuses"] = refused
        expected["a precision on a format outside the probed universe"] = emptyList()
        assertEquals(expected, actual, "where the checker reports the precision gate")
    }

    /** The check beside a conflicting precision adds nothing: A12 reports the precision code alone. */
    @Test
    fun theCheckBesideAConflictingPrecisionDrawsOnlyThePrecisionCode() {
        assertEquals(
            partial,
            date("""{"format":"MM","youngerThan1900Check":true,"datePrecision":"DAY_OPTIONAL"}"""),
            "kernel 31.2 retired the year gate, so only the precision verdict remains",
        )
    }
}
