package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The models that separate a scoped walk's SECOND branch from its first, which is where the equivalence broke.
 *
 * [scopedWalkDivergences] is also run over this module's two wiring fixtures and every parity-corpus model,
 * and it was green there while four families answered differently — because a fixture reaches a family through
 * one branch. `eachRegistrationProducesExactlyTheFamiliesItClaims` forces every family to report *a* row, so
 * the differential is non-vacuous per PRODUCER; it was being read as non-vacuous per BRANCH, which is a
 * different claim and the one that matters when a family's set question hides two frames below its entry point
 * (cold review, 2026-09-18).
 *
 * **Each model below reaches a branch that asks a question about the MODEL rather than about the pass's own
 * declaration**, and each was measured divergent before the helpers were repointed:
 *
 * | model | family | whole model said | the scoped passes said |
 * |---|---|---|---|
 * | [GROUP_OPERAND] | `FIELD_VALUES_NOT_UNIQUE_OPERAND_KIND` | one `MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED` | nothing — finding DROPPED |
 * | [CROSSED_REPETITION_LIST] | `ENTITY_LIST_PRE_GATES` | nothing | `MVK_DUPLICATE_PARAM1` — finding INVENTED |
 * | [CROSSED_REPETITION_ROUNDING] | `ROUNDING_OPERANDS` | nothing | `MVK_NO_NUMBER` — finding INVENTED |
 * | [CROSSED_REPETITION_COMPARISON] | `TEMPORAL_COMPARISON_AGREEMENT` | nothing | `MVK_INVALID_COMPARE_TO_DATE` — finding INVENTED |
 *
 * The invented half reached `check`'s public report, so this is not a reuse-quality row: the rows below are the
 * regression locks for a wrong verdict a consumer could read.
 */
class ScopedWalkBranchDiscriminatorsTest {

    @Test
    fun aGroupOperandExpandedToTheFieldsBeneathItAnswersTheSameEitherWay() {
        assertDiscriminates(GROUP_OPERAND)
        // The dropped verdict must REACH the report: a group operand whose fields carry two different date
        // formats is what A12 refuses here, and a scoped pass that expands the group against one declaration
        // finds no field at all and returns silently.
        assertTrue(
            codesAt(GROUP_OPERAND, "/Order/Unique").contains("MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED"),
            "the operand-kind verdict is missing from the report, so the group expanded to no fields",
        )
    }

    @Test
    fun anEntityListOperandCrossingAnUnaddressedRepetitionAnswersTheSameEitherWay() {
        assertDiscriminates(CROSSED_REPETITION_LIST)
        assertTrue(
            "MVK_DUPLICATE_PARAM1" !in codesAt(CROSSED_REPETITION_LIST, "/Order/Sum"),
            "an invented duplicate-parameter verdict is in the report: the pass judged an operand whose " +
                "repetition it could not see, which is the class this family yields on",
        )
    }

    @Test
    fun aRoundingOperandCrossingAnUnaddressedRepetitionAnswersTheSameEitherWay() {
        assertDiscriminates(CROSSED_REPETITION_ROUNDING)
        assertTrue(
            "MVK_NO_NUMBER" !in codesAt(CROSSED_REPETITION_ROUNDING, "/Order/Rnd"),
            "an invented non-number verdict is in the report: the pass resolved a bare reference as if it " +
                "crossed no repetition",
        )
    }

    @Test
    fun aComparisonCrossingAnUnaddressedRepetitionAnswersTheSameEitherWay() {
        assertDiscriminates(CROSSED_REPETITION_COMPARISON)
        assertTrue(
            "MVK_INVALID_COMPARE_TO_DATE" !in codesAt(CROSSED_REPETITION_COMPARISON, "/Order/Probe"),
            "an invented date-comparison verdict is in the report, beside the wildcard class the model is " +
                "actually refused for",
        )
    }

    /**
     * One model, two assertions that a discriminator needs: that it is still a model at all, and that the
     * walk's two readings agree on it.
     *
     * The admission assertion is the reach control. A discriminator that stops being admitted stops reaching
     * any family, and then the equivalence below holds trivially — which is exactly how a row like this rots.
     */
    private fun assertDiscriminates(json: String) {
        val model = ModelSource(json)
        assertIs<Admission.Admitted>(admit(model), "the discriminator must be a model the checker prepares")
        assertEquals(emptyList(), scopedWalkDivergences(model),
            "a family asked per declaration answered differently from the same family over the model")
    }

    private fun codesAt(json: String, at: String): List<String> =
        check(ModelSource(json)).findings.filter { it.at == at }.map { it.code }

    private companion object {

        /** `FieldValuesNotUnique` over a GROUP, which the family expands to the fields beneath it. */
        private val GROUP_OPERAND = """
            {"header":{"id":"discriminator","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Lines","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Placed","Field":{"fieldType":{
                    "type":"DateType","DateType":{"format":"yyyy-MM-dd"}}}},
                  {"type":"Field","id":"f2","name":"Shipped","Field":{"fieldType":{
                    "type":"DateType","DateType":{"format":"dd.MM.yyyy"}}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Unique","Rule":{
                  "errorCondition":"FieldValuesNotUnique(Lines)","errorEntityRelPath":"../Lines/Placed",
                  "errorCode":"U1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
        """.trimIndent()

        /** An entity-list operand under a repetition the computation's own level does not address. */
        private val CROSSED_REPETITION_LIST = """
            {"header":{"id":"discriminator","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"repeatability":9,"elements":[
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"elements":[
                  {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f2","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Field","id":"f3","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Computation","id":"c0","name":"Sum","Computation":{
                  "computedFieldRelPath":"../Total",
                  "computationAlternatives":[{"operation":"{SumOfProducts(Lines/Amount, Lines/Amount)}"}]}}
              ]}}
             ]}}}
        """.trimIndent()

        /** A rounding operand that is a bare reference across a repetition. */
        private val CROSSED_REPETITION_ROUNDING = """
            {"header":{"id":"discriminator","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"elements":[
                  {"type":"Field","id":"f1","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Field","id":"f3","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Computation","id":"c0","name":"Rnd","Computation":{
                  "computedFieldRelPath":"../Total",
                  "computationAlternatives":[{"operation":"{RoundUpValue(Lines/Note, 2)}"}]}}
              ]}}
             ]}}}
        """.trimIndent()

        /** A comparison whose operands both sit under a repetition the rule's level does not address. */
        private val CROSSED_REPETITION_COMPARISON = """
            {"header":{"id":"discriminator","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"elements":[
                  {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f4","name":"Placed","Field":{"fieldType":{
                    "type":"DateType","DateType":{"format":"yyyy-MM-dd"}}}}
                ]}},
                {"type":"Field","id":"f3","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"Lines/Placed > Lines/Amount",
                  "errorEntityRelPath":"../Total",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
