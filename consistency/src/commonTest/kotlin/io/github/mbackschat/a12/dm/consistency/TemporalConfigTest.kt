package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF314] — a date field's config against its declared format.
 *
 * Every row mirrors one measured through stored JSON against the real kernel
 * (`rulekit.validate.laws.TemporalConfigWireShapeLawsTest`, whose own grid settles the MEMBER NAMES this
 * family reads, and `TemporalDeclarationConfigAdmissionLawsTest` / `TemporalAdditionalCheckCarrierLawsTest`,
 * which sweep both gates across the whole admitted format vocabulary).
 *
 * **The silent rows are the ones that could do damage**, because this family reports A12's own consistency
 * codes on models A12 reads and builds. A `false` check, a `FULL` precision and the range carrier are each a
 * shape A12 ACCEPTS — reporting one would refuse a model the kernel takes, and nothing downstream could catch
 * it. A format outside the admitted vocabulary draws neither config code: A12 refuses it with its own sentence
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

    private val pre1900 = listOf("MVK_ADDITIONAL_CHECK_INVALID")
    private val partial = listOf("MVK_OPTIONAL_DATE_TYPE_INVALID")
    private val refused = listOf(RK_DATE_FORMAT_INVALID)

    @Test
    fun thePre1900CheckIsReportedOnAYearFreeFormatAndNowhereElse() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain date format — the control"] = date("""{"format":"yyyy-MM-dd"}""")
        actual["the check on a year-free format"] = date("""{"format":"MM","youngerThan1900Check":true}""")
        actual["the check on a year-bearing format"] =
            date("""{"format":"yyyy-MM","youngerThan1900Check":true}""")
        actual["the check written FALSE"] = date("""{"format":"MM","youngerThan1900Check":false}""")
        actual["the check with no format, so the default applies"] = date("""{"youngerThan1900Check":true}""")
        actual["the check on a format A12 refuses"] =
            date("""{"format":"MM.yyyy","youngerThan1900Check":true}""")
        actual["the check on a format outside the probed universe"] =
            date("""{"format":"QQQ","youngerThan1900Check":true}""")
        // The trailing-separator format, admitted and year-free — the thirteenth member, which a checker
        // keyed on a twelve-format list would have left silent (KERNEL-FINDINGS KF313).
        actual["the check on the trailing-dot format"] =
            date("""{"format":"dd.MM.","youngerThan1900Check":true}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain date format — the control"] = emptyList()
        expected["the check on a year-free format"] = pre1900
        expected["the check on a year-bearing format"] = emptyList()
        // The gate is on the VALUE: `false` on a year-free format is a declaration A12 accepts.
        expected["the check written FALSE"] = emptyList()
        // The builder's default is `yyyy-MM-dd`, which carries a year and is a complete pure date, so A12
        // admits a body with no format on BOTH gates — the two rows lock that A12 answer, not the branch the
        // checker takes to reach it (reading the default and reading nothing are verdict-equivalent here).
        expected["the check with no format, so the default applies"] = emptyList()
        // A refused format draws its own sentence and no config gate (KERNEL-FINDINGS KF648).
        expected["the check on a format A12 refuses"] = refused
        // Silent by design: the admitted set is a probe's result rather than a closed one, so a format
        // outside it is undiscovered and refusing it would rest on the limit of that probe.
        expected["the check on a format outside the probed universe"] = emptyList()
        expected["the check on the trailing-dot format"] = pre1900
        assertEquals(expected, actual, "where the checker reports the pre-1900 gate")
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

    /**
     * The carrier denominator: three types declare the pre-1900 member and only two are gated on it.
     *
     * A family that read the member wherever it found it would refuse the range row, which A12 admits — and
     * the range carrier is the one whose law is the OPPOSITE of its siblings', so it cannot be inferred.
     * Its exemption is measured (`rulekit.validate.laws.TemporalAdditionalCheckCarrierLawsTest`).
     */
    @Test
    fun theCheckIsGatedOnTwoOfItsThreeCarriers() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a DATE with a year-free format"] = date("""{"format":"MM","youngerThan1900Check":true}""")
        actual["a FRAGMENT with a year-free format"] = codes(
            """{"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"MM",""" +
                """"youngerThan1900Check":true}}""",
        )
        actual["a FRAGMENT with a year-bearing format"] = codes(
            """{"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"dd.MM.yyyy",""" +
                """"youngerThan1900Check":true}}""",
        )
        actual["a RANGE with a year-free pair — EXEMPT"] = codes(
            """{"type":"DateRangeType","DateRangeType":{"format":"MM","rangeSeparator":"/",""" +
                """"youngerThan1900Check":true}}""",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["a DATE with a year-free format"] = pre1900
        expected["a FRAGMENT with a year-free format"] = pre1900
        expected["a FRAGMENT with a year-bearing format"] = emptyList()
        expected["a RANGE with a year-free pair — EXEMPT"] = emptyList()
        assertEquals(expected, actual, "which carriers the pre-1900 gate is asked of")
    }

    /** Both gates are reported independently, exactly as A12 reports both codes for one declaration. */
    @Test
    fun bothGatesAreReportedWhenOneDeclarationFailsBoth() {
        assertEquals(
            pre1900 + partial,
            date("""{"format":"MM","youngerThan1900Check":true,"datePrecision":"DAY_OPTIONAL"}"""),
            "neither gate pre-empts the other, so a checker reporting one is not reporting the other",
        )
    }
}
