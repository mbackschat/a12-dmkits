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

    /** Each filter is spliced raw into its declaration, or omitted when null; [ruleCondition] defaults sound. */
    private fun source(
        ruleCondition: String = "FieldFilled(Price)",
        ruleFilter: String? = null,
        computationFilter: String? = null,
        fieldFilter: String? = null,
        ruleErrorCode: String = "\"PROBE\"",
    ) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{${
            fieldFilter?.let { """"variantFilter":$it""" } ?: ""
        }}},
            {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
              "errorCondition":"$ruleCondition","errorEntityRelPath":"../Price",
              "errorCode":$ruleErrorCode,"errorMessage":[{"locale":"en_US","text":"x"}]${
            ruleFilter?.let { ""","variantFilter":$it""" } ?: ""
        }}},
            {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
              "computationAlternatives":[{"operation":"42.00"}]${
            computationFilter?.let { ""","variantFilter":$it""" } ?: ""
        }}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun model(
        ruleCondition: String = "FieldFilled(Price)",
        ruleFilter: String? = null,
        computationFilter: String? = null,
        fieldFilter: String? = null,
    ) = prepare(
        assertIs<Admission.Admitted>(admit(source(ruleCondition, ruleFilter, computationFilter, fieldFilter))),
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

    /**
     * A group or field carrying the member is not this family's subject, so it is left alone.
     *
     * The fixture gives the FIELD a filter on purpose. Without that the assertion is vacuous — widening the
     * family to every declared kind would change nothing, because no other declaration carries the member at
     * all, and the test would pass while claiming to exclude a carrier it never presented.
     */
    @Test
    fun theFamilyJudgesOnlyRulesAndComputations() {
        val gaps = model(ruleFilter = only, computationFilter = only, fieldFilter = only).variantFilterGaps()
        assertEquals(listOf("/Catalog/PriceRule", "/Catalog/PriceCalc"), gaps.map { it.at },
            "no field or group declaration may appear here")
        assertEquals(emptyList(), model(fieldFilter = only).variantFilterFindings().map { it.code },
            "a field's filter is unmeasured, so it draws no finding either")
    }

    /**
     * THE REPORT-LEVEL CONTRACT: an `ONLY`-filtered declaration draws no finding, whatever is wrong with it.
     *
     * <p>Each family is individually correct about the declaration it read and wrong only about whether A12
     * read it too, so this is invisible to every per-family test — which is exactly how it shipped. The whole
     * report is the unit under test here for that reason.
     *
     * <p><b>The breadth is measured, not assumed.</b> Seven ways of breaking a filtered rule were run against
     * the Kernel unfiltered and under `ONLY` ([KERNEL-FINDINGS KF299],
     * `rulekit.validate.laws.RuleVariantFilterLawsTest`): the reference, all three parse phases, the error
     * target and the error message are all ADMITTED under the filter. So there is no surviving subset to keep
     * judging, and suppressing one family rather than the declaration would still over-reject.
     */
    @Test
    fun anOnlyFilteredDeclarationDrawsNoFindingWhateverIsWrongWithIt() {
        val broken = mapOf(
            "a condition naming no entity" to "FieldFilled(Nonexistent)",
            "a condition that cannot be tokenized" to "FieldFilled(\\\"Price)",
            "a condition that ends early" to "FieldFilled(Price) And",
        )
        val actual = mutableMapOf<String, List<String>>()
        for ((label, condition) in broken) {
            actual["$label, unfiltered — the positive control"] = codesFor(condition, filter = null)
            actual["$label, ONLY"] = codesFor(condition, filter = only)
            actual["$label, ALL_EXCEPT — still judged"] = codesFor(condition, filter = allExcept)
        }

        val expected = mutableMapOf<String, List<String>>()
        expected["a condition naming no entity, unfiltered — the positive control"] = listOf("MVK_INVALID_ENTITY")
        expected["a condition naming no entity, ONLY"] = emptyList()
        expected["a condition naming no entity, ALL_EXCEPT — still judged"] = listOf("MVK_INVALID_ENTITY")
        expected["a condition that cannot be tokenized, unfiltered — the positive control"] =
            listOf("MVK_LEXER_STANDARD_ERROR")
        expected["a condition that cannot be tokenized, ONLY"] = emptyList()
        expected["a condition that cannot be tokenized, ALL_EXCEPT — still judged"] =
            listOf("MVK_LEXER_STANDARD_ERROR")
        expected["a condition that ends early, unfiltered — the positive control"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["a condition that ends early, ONLY"] = emptyList()
        expected["a condition that ends early, ALL_EXCEPT — still judged"] = listOf("MVK_INCOMPLETE_INPUT")
        assertEquals(expected, actual, "what the whole report says about a filtered declaration")
    }

    /**
     * The exemption, and it rests on measured rows rather than on symmetry.
     *
     * A12's READER runs a tier before any variant logic, so a shape it refuses is refused whatever the filter
     * says — measured on an `errorCode` that is JSON null and on a message entry whose locale the reader
     * rejects, both UNREADABLE filtered and unfiltered
     * (`rulekit.validate.laws.RuleVariantFilterLawsTest`). A filter suppresses what A12 would SAY ABOUT a
     * declaration and cannot rescue a model that fails to BUILD, so a checker that dropped every finding for a
     * filtered declaration would report an unloadable model as merely unexamined.
     *
     * The `ONLY` filter here is VALID on purpose: an unbindable filter shape is not itself suppressed, so it
     * cannot exercise the exemption, and a test using one passes whether the exemption exists or not.
     */
    @Test
    fun aFindingThatTheModelWillNotBuildSurvivesTheFilter() {
        val report = check(source(ruleFilter = only, ruleErrorCode = "null"))
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), report.findings.filter { it.at == "/Catalog/PriceRule" }
            .map { it.code }, "a model that does not load is not a verdict the filter can hide")
    }

    /**
     * Suppression is per declaration, not per report.
     *
     * The unfiltered computation in the same model keeps its findings — without that row, a checker that
     * silenced the whole report whenever any declaration carried a filter would pass this test while hiding
     * every other defect in the model.
     */
    @Test
    fun onlyTheFilteredDeclarationGoesUnjudged() {
        val report = check(source(ruleCondition = "FieldFilled(Nonexistent)", ruleFilter = only))
        assertEquals(emptyList(), report.findings.filter { it.at == "/Catalog/PriceRule" },
            "the filtered rule's findings are gone")
        assertTrue(
            report.findings.any { it.at == "/Catalog/PriceCalc" },
            "the unfiltered computation is still judged: ${report.findings}",
        )
        assertTrue(
            report.gaps.any { it.at == "/Catalog/PriceRule" },
            "and the gap says so, so a clean report is not read as coverage: ${report.gaps}",
        )
    }

    private fun codesFor(condition: String, filter: String?) =
        check(source(ruleCondition = condition, ruleFilter = filter)).findings
            .filter { it.at == "/Catalog/PriceRule" }.map { it.code }
}
