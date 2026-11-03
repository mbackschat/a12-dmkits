package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the checker reports for kernel 31.1.1's `SumOfTerms`, row for row against the kernel.
 *
 * Every row and both fixtures mirror ones measured on the real kernel: the iteration-entity rows by
 * `rulekit.validate.laws.SumOfTermsIterationEntityLawsTest` (KERNEL-FINDINGS KF656), the term and error-field rows
 * by `rulekit.validate.laws.SumOfTermsTermLawsTest` (KF670). Each fixture is the one its law test measured on, so
 * no row's code is carried across models. An empty list is A12's admission.
 */
class SumOfTermsAdmissionTest {

    /** The iteration-entity carrier: `Holder`, and `Claims[3] { Amount, Reserve }`. */
    private fun claims(errorField: String, condition: String): ModelSource = ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy-sum-of-terms","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":3,"elements":[
                  {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f2","name":"Reserve","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                ${rule(errorField, condition)}
              ]}}
             ]}}}
            """.trimIndent(),
        )

    /**
     * The term carrier: `Holder`, `Total`, a non-repeatable `Address { Zip }`, `Vehicles[2] { Value }`, and
     * `Claims[3] { ClaimRef (index), Status, Amount, Assessment { Score }, Parts[4] { Qty, Price } }`.
     */
    private fun policy(errorField: String, condition: String): ModelSource = policyWith(rule(errorField, condition))

    private fun policyWith(member: String): ModelSource = ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy-sum-of-terms-term","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Address","Group":{"elements":[
                  {"type":"Field","id":"f2","name":"Zip","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Vehicles","Group":{"repeatability":2,"elements":[
                  {"type":"Field","id":"f3","name":"Value","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Group","id":"g3","name":"Claims","Group":{"repeatability":3,"indexFieldName":"ClaimRef",
                  "elements":[
                  {"type":"Field","id":"f4","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f5","name":"Status","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g4","name":"Assessment","Group":{"elements":[
                    {"type":"Field","id":"f7","name":"Score","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}},
                  {"type":"Group","id":"g5","name":"Parts","Group":{"repeatability":4,"elements":[
                    {"type":"Field","id":"f8","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Field","id":"f9","name":"Price","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                ]}},
                $member
              ]}}
             ]}}}
            """.trimIndent(),
        )

    private fun rule(errorField: String, condition: String): String =
        """{"type":"Rule","id":"r0","name":"Probe","Rule":{"errorCondition":"${condition.replace("\"", "\\\"")}",""" +
            """"errorEntityRelPath":"../$errorField","errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}"""

    private fun computation(target: String, operation: String): String =
        """{"type":"Computation","id":"c0","name":"Probe","Computation":{"computedFieldRelPath":"../$target",""" +
            """"computationAlternatives":[{"operation":"$operation"}],""" +
            """"errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}"""

    private fun codes(source: ModelSource): List<String> =
        checkRead(source).findings.filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }

    private fun atPolicy(condition: String) = codes(policy("Holder", "FieldFilled(Holder) And $condition"))

    @Test
    fun theIterationEntityIsAStarredRepeatableGroupWithAnIteratingTerm() {
        val actual = linkedMapOf(
            "the control" to codes(claims("Holder", "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount]) > 0")),
            "a term that is an operation" to codes(claims("Holder",
                "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] + [Claims/Reserve]) > 0")),
            "a filtered group" to codes(claims("Holder",
                "FieldFilled(Holder) And SumOfTerms(Claims* Having [Claims/Amount] > 5, [Claims/Amount]) > 0")),
            "an unstarred group the rule iterates" to
                codes(claims("Claims/Amount", "SumOfTerms(Claims, [Claims/Amount]) > 0")),
            "an unstarred group the rule does not iterate" to
                codes(claims("Holder", "SumOfTerms(Claims, [Claims/Amount]) > 0")),
            "a term that does not iterate" to codes(claims("Holder", "SumOfTerms(Claims*, 5) > 0")),
            "a field in the slot" to codes(claims("Holder", "SumOfTerms(Claims*/Amount, [Claims/Amount]) > 0")),
        )
        assertEquals(linkedMapOf(
            "the control" to listOf(),
            "a term that is an operation" to listOf(),
            "a filtered group" to listOf(),
            "an unstarred group the rule iterates" to listOf("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"),
            "an unstarred group the rule does not iterate" to listOf("MVK_NO_WILDCARD"),
            "a term that does not iterate" to listOf("MVK_NO_ITERATION_FOR_LOWEST_WILDCARD"),
            "a field in the slot" to listOf("MVK_NO_FIELDS_ALLOWED"),
        ), actual)
    }

    @Test
    fun theTermIsANumberThatIteratesOnlyTheIterationEntitysPath() {
        val actual = linkedMapOf(
            "the control" to atPolicy("SumOfTerms(Claims*, [Claims/Amount]) > 0"),
            "a string term" to atPolicy("SumOfTerms(Claims*, [Claims/Status]) > 0"),
            "another repeatable group" to atPolicy("SumOfTerms(Claims*, [Claims/Amount] + [Vehicles/Value]) > 0"),
            "a non-repeatable group" to atPolicy("SumOfTerms(Claims*, [Claims/Amount] + [Address/Zip]) > 0"),
            "a semantic index" to atPolicy("SumOfTerms(Claims*, [Claims/Amount] + [Claims/Amount For \"C1\"]) > 0"),
            "the outer marker" to atPolicy("SumOfTerms(Claims*, [\$Claims/Amount]) > 0"),
            "a filtered aggregate" to atPolicy(
                "SumOfTerms(Claims*, [Claims/Amount] + NumberOfFilledFields(Vehicles*/Value Having [Vehicles/Value] > 1)) > 0"),
            "a filtered aggregate its own filter check refuses" to atPolicy(
                "SumOfTerms(Claims*, NumberOfFilledGroups(Claims/Parts* Having [Claims/Parts/Qty] > 1)) > 0"),
            "the same aggregate unfiltered" to
                atPolicy("SumOfTerms(Claims*, [Claims/Amount] + NumberOfFilledFields(Vehicles*/Value)) > 0"),
            "an unfiltered aggregate below the wildcard" to atPolicy("SumOfTerms(Claims*, Sum(Claims/Parts*/Qty)) > 0"),
            "a nested SumOfTerms" to
                atPolicy("SumOfTerms(Claims*, SumOfTerms(Claims/Parts*, [Claims/Parts/Qty])) > 0"),
        )
        assertEquals(linkedMapOf(
            "the control" to listOf(),
            "a string term" to listOf("MVK_NO_NUMBER"),
            "another repeatable group" to listOf("MVK_INVALID_ITERATION_IN_SET_EXPRESSION"),
            "a non-repeatable group" to listOf(),
            "a semantic index" to listOf("MVK_SEMANTIC_INDEX_NOT_ALLOWED"),
            "the outer marker" to listOf("MVK_INVALID_OUTER_ITERATION"),
            "a filtered aggregate" to listOf("MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION"),
            "a filtered aggregate its own filter check refuses" to listOf("MVK_NO_WILDCARD"),
            "the same aggregate unfiltered" to listOf(),
            "an unfiltered aggregate below the wildcard" to listOf(),
            "a nested SumOfTerms" to listOf("MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION"),
        ), actual)
    }

    @Test
    fun aNestedIterationEntityIsIteratedAtItsLowestLevel() {
        val actual = linkedMapOf(
            "both levels starred, the term iterates both" to
                atPolicy("SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty] * [Claims/Parts/Price]) > 0"),
            "both levels starred, the term iterates only the upper" to
                atPolicy("SumOfTerms(Claims*/Parts*, [Claims/Amount]) > 0"),
            "the upper level iterated by the rule" to codes(policy("Claims/Amount",
                "FieldFilled(Claims/Amount) And SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 10")),
            "the upper level neither starred nor iterated" to atPolicy("SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 10"),
            "a starred non-repeatable group" to atPolicy("SumOfTerms(Address*, [Address/Zip]) > 0"),
            "a repeatable group below the star left unstarred" to
                atPolicy("SumOfTerms(Claims*/Parts, [Claims/Parts/Qty]) > 0"),
            "a non-repeatable group below the star" to
                atPolicy("SumOfTerms(Claims*/Assessment, [Claims/Assessment/Score]) > 0"),
        )
        assertEquals(linkedMapOf(
            "both levels starred, the term iterates both" to listOf(),
            "both levels starred, the term iterates only the upper" to listOf("MVK_NO_ITERATION_FOR_LOWEST_WILDCARD"),
            "the upper level iterated by the rule" to listOf(),
            "the upper level neither starred nor iterated" to listOf("MVK_NO_WILDCARD"),
            "a starred non-repeatable group" to listOf("MVK_INVALID_WILDCARD"),
            "a repeatable group below the star left unstarred" to listOf("MVK_NO_WILDCARD"),
            "a non-repeatable group below the star" to listOf("MVK_GROUP_NOT_REPEATABLE"),
        ), actual)
    }

    @Test
    fun theRemainingArmsOfTheIterationAndTermWalk() {
        val actual = linkedMapOf(
            "a semantic index on the iteration entity" to atPolicy("SumOfTerms(Claims* For \"C1\", [Claims/Amount]) > 0"),
            "an iterated level below a star the term writes" to
                atPolicy("SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty] + Sum(Claims*/Parts/Price)) > 0"),
            "the error field in another repeatable group" to
                codes(policy("Vehicles/Value", "SumOfTerms(Claims*, [Claims/Amount]) > 0")),
            "the error field in the upper level of a nested iteration" to
                codes(policy("Claims/Status", "SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty]) > 0")),
            "the error field in the lower level of a nested iteration" to
                codes(policy("Claims/Parts/Price", "SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty]) > 0")),
        )
        assertEquals(linkedMapOf(
            "a semantic index on the iteration entity" to listOf("MVK_SEMANTIC_INDEX_AND_WILDCARD"),
            "an iterated level below a star the term writes" to listOf("MVK_NO_WILDCARD"),
            "the error field in another repeatable group" to listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
            "the error field in the upper level of a nested iteration" to listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
            "the error field in the lower level of a nested iteration" to listOf(),
        ), actual)
    }

    @Test
    fun aComputationMayAssignATermSum() {
        val actual = linkedMapOf(
            "a term sum" to codes(policyWith(computation("Total", "SumOfTerms(Claims*, [Claims/Amount] * 2)"))),
            "a term that does not iterate" to codes(policyWith(computation("Total", "SumOfTerms(Claims*, 5)"))),
        )
        assertEquals(linkedMapOf(
            "a term sum" to listOf(),
            "a term that does not iterate" to listOf("MVK_NO_ITERATION_FOR_LOWEST_WILDCARD"),
        ), actual)
    }

    @Test
    fun theTermAndTheFilterEachReferenceTheErrorField() {
        fun total(condition: String) = codes(policy("Total", condition))
        val actual = linkedMapOf(
            "read in the term" to total("SumOfTerms(Claims*, [Claims/Amount] + [Total]) > 0"),
            "read in the term, marked" to total("SumOfTerms(Claims*, [Claims/Amount] + [\$Total]) > 0"),
            "read in the filter" to total("SumOfTerms(Claims* Having [Claims/Amount] > [Total], [Claims/Amount]) > 0"),
            "read in the filter, marked" to
                total("SumOfTerms(Claims* Having [Claims/Amount] > [\$Total], [Claims/Amount]) > 0"),
            "read nowhere" to total("SumOfTerms(Claims*, [Claims/Amount]) > 0"),
            "read beside the sum" to total("FieldFilled(Total) And SumOfTerms(Claims*, [Claims/Amount]) > 0"),
            "inside the iteration group, read by no term" to
                codes(policy("Claims/Status", "SumOfTerms(Claims*, [Claims/Amount]) > 0")),
            "inside the iteration group, read beside the sum" to codes(policy("Claims/Status",
                "FieldFilled(Claims/Status) And SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 0")),
        )
        assertEquals(linkedMapOf(
            "read in the term" to listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
            "read in the term, marked" to listOf("MVK_INVALID_OUTER_ITERATION"),
            "read in the filter" to listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
            "read in the filter, marked" to listOf(),
            "read nowhere" to listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
            "read beside the sum" to listOf(),
            "inside the iteration group, read by no term" to listOf(),
            "inside the iteration group, read beside the sum" to listOf(),
        ), actual)
    }
}
