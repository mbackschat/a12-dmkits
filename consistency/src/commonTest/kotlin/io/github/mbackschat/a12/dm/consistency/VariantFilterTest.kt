package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A declaration's `variantFilter` — the one family whose main output is a COVERAGE GAP rather than a finding.
 *
 * An `ONLY` filter takes the declaration out of what `checkConsistency` judges entirely ([KERNEL-FINDINGS
 * KF299], measured as a within-model 2×2 on the rule carrier and re-measured on the computation carrier,
 * kernel 30.8.1, 2026-09-12). A12 ADMITS such a model and reports nothing — no finding, no warning, no code —
 * so the correct response here is to say the declaration was not examined. Reporting an ERROR instead would
 * reject a model A12 accepts, which is the one direction this checker must never take.
 *
 * The reader-refused shapes are findings as usual, because there the model does not load at all.
 */
class VariantFilterTest {

    /** [filterJson] is spliced raw into the named declaration, or omitted when null. */
    private fun model(ruleFilter: String? = null, computationFilter: String? = null) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{}},
                        {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                          "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price"${
                        ruleFilter?.let { ""","variantFilter":$it""" } ?: ""
                    }}},
                        {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
                          "computationAlternatives":[{"operation":"42.00"}]${
                        computationFilter?.let { ""","variantFilter":$it""" } ?: ""
                    }}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private val only = """{"filterType":"ONLY","variants":["premium"]}"""
    private val allExcept = """{"filterType":"ALL_EXCEPT","variants":["premium"]}"""

    /**
     * The filter TYPE alone decides, and it decides for BOTH carriers.
     *
     * The variants a filter names are irrelevant — an empty list and an absent `variants` member suppress
     * exactly as `["premium"]` does, which is what the laws tier carries through a BROKEN declaration to
     * establish. So this checker keys on the type and never inspects the list.
     */
    @Test
    fun anOnlyFilterIsReportedAsAnUnexaminedDeclaration() {
        val actual = mutableMapOf<String, List<String?>>()
        actual["no filter at all — the control"] = model().variantFilterGaps().map { it.at }
        actual["a rule filtered ONLY"] = model(ruleFilter = only).variantFilterGaps().map { it.at }
        actual["a rule filtered ALL_EXCEPT"] = model(ruleFilter = allExcept).variantFilterGaps().map { it.at }
        actual["a computation filtered ONLY"] =
            model(computationFilter = only).variantFilterGaps().map { it.at }
        actual["a computation filtered ALL_EXCEPT"] =
            model(computationFilter = allExcept).variantFilterGaps().map { it.at }
        actual["both filtered ONLY"] =
            model(ruleFilter = only, computationFilter = only).variantFilterGaps().map { it.at }
        actual["ONLY naming an empty variants list"] =
            model(ruleFilter = """{"filterType":"ONLY","variants":[]}""").variantFilterGaps().map { it.at }
        actual["ONLY with no variants member"] =
            model(ruleFilter = """{"filterType":"ONLY"}""").variantFilterGaps().map { it.at }

        val expected = mutableMapOf<String, List<String?>>()
        expected["no filter at all — the control"] = emptyList()
        expected["a rule filtered ONLY"] = listOf("/Catalog/PriceRule")
        // ALL_EXCEPT is judged normally, so claiming a gap there would understate what this checker examined.
        expected["a rule filtered ALL_EXCEPT"] = emptyList()
        expected["a computation filtered ONLY"] = listOf("/Catalog/PriceCalc")
        expected["a computation filtered ALL_EXCEPT"] = emptyList()
        expected["both filtered ONLY"] = listOf("/Catalog/PriceRule", "/Catalog/PriceCalc")
        expected["ONLY naming an empty variants list"] = listOf("/Catalog/PriceRule")
        expected["ONLY with no variants member"] = listOf("/Catalog/PriceRule")
        assertEquals(expected, actual, "which declarations A12 never examined because a filter excluded them")
    }

    /**
     * A suppressed declaration is a GAP and never a finding — the distinction the whole family exists for.
     *
     * A12 accepts the model, so an ERROR here would reject what the Kernel admits. The gap says the opposite
     * and correct thing: nothing is wrong with this declaration as far as anyone knows, because nobody looked.
     */
    @Test
    fun aSuppressedDeclarationProducesNoFinding() {
        assertEquals(emptyList(), model(ruleFilter = only).variantFilterFindings().map { it.code },
            "an ONLY filter is a coverage gap, never a rejection")
        assertTrue(
            model(ruleFilter = only).variantFilterGaps().single().reason.contains("ONLY"),
            "the gap must name the filter that caused it so an author can act on it",
        )
    }

    /** The shapes A12's own reader refuses — the model does not load, so no verdict about it exists. */
    @Test
    fun aMemberShapeA12sReaderRefusesIsReportedAsUnbuildable() {
        val refused = mapOf(
            "an unknown filterType" to """{"filterType":"SOMETIMES","variants":["premium"]}""",
            "no filterType" to """{"variants":["premium"]}""",
            "a string instead of an object" to """"ONLY"""",
        )
        val actual = refused.mapValues { (_, json) -> model(ruleFilter = json).variantFilterFindings().map { it.code } }
        val expected = refused.mapValues { listOf(CK_MODEL_NOT_BUILDABLE) }
        assertEquals(expected, actual, "the shapes A12 cannot bind")
    }

    /**
     * An explicit `null` is ADMITTED here, unlike `errorCodesToSuppress` beside it.
     *
     * The difference is the binding, not DM-JSON: this member is reached through an `Optional` accessor while
     * the suppression list is bound `@NonNull`. It is measured on this member rather than carried across from
     * its neighbour, because that is the carrier confusion that has cost a correction here before.
     */
    @Test
    fun anExplicitNullIsAdmittedAndSaysNothingAboutCoverage() {
        assertEquals(emptyList(), model(ruleFilter = "null").variantFilterFindings().map { it.code })
        assertEquals(emptyList(), model(ruleFilter = "null").variantFilterGaps())
    }

    /** A group or field carrying the member is not this family's subject, so it is left alone. */
    @Test
    fun theFamilyJudgesOnlyRulesAndComputations() {
        val gaps = model(ruleFilter = only, computationFilter = only).variantFilterGaps()
        assertEquals(listOf("/Catalog/PriceRule", "/Catalog/PriceCalc"), gaps.map { it.at },
            "no field or group declaration may appear here")
    }
}
