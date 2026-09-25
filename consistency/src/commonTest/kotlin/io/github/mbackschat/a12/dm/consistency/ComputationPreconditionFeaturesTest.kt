package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "which predicates a computation precondition may not use".
 *
 * Every row mirrors one measured against the real Kernel by
 * `rulekit.validate.laws.ComputationPreconditionLanguageFeatureLawsTest`. The RULE rows are not extra
 * coverage: both predicates are ordinary there, so a checker refusing them wherever it met them would refuse
 * a legal model — and the two slots read their condition text through the same code.
 */
class ComputationPreconditionFeaturesTest {

    private val prohibited = "MVK_ERROR_INVALID_LANGUAGE_FEATURE"

    private fun computing(
        precondition: String? = null,
        commonPrecondition: String? = null,
        operation: String = "1",
    ): List<String> = check(
        ModelSource(model(precondition = precondition, commonPrecondition = commonPrecondition,
            operation = operation)),
    ).findings.map { it.code }

    private fun ruleSaying(condition: String): List<String> =
        check(ModelSource(model(condition = condition))).findings.map { it.code }

    @Test
    fun aPreconditionMayNotUseAnApplicationConditionOrRepetitionNotUnique() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary precondition — the control"] = computing(precondition = "FieldFilled(Lines/Sku)")
        actual["CustomCondition"] = computing(precondition = "FieldFilled(Lines/Sku) And CustomCondition NotReverse")
        actual["RepetitionNotUnique"] =
            computing(precondition = "FieldFilled(Lines/Sku) And RepetitionNotUnique(Lines/Sku)")
        actual["CustomCondition as the COMMON precondition"] =
            computing(commonPrecondition = "FieldFilled(Lines/Sku) And CustomCondition NotReverse")
        actual["CustomCondition in a RULE — the control"] =
            ruleSaying("FieldFilled(Lines/Sku) And CustomCondition NotReverse")
        actual["RepetitionNotUnique in a RULE — the control"] =
            ruleSaying("FieldFilled(Lines/Sku) And RepetitionNotUnique(Lines/Sku)")
        actual["RepetitionNotUnique in the OPERATION — the control"] =
            computing(operation = "RepetitionNotUnique(Lines/Sku)")

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary precondition — the control"] = emptyList()
        expected["CustomCondition"] = listOf(prohibited)
        expected["RepetitionNotUnique"] = listOf(prohibited)
        expected["CustomCondition as the COMMON precondition"] = listOf(prohibited)
        // The rows that keep the family on the computation. A12 admits both in a rule condition.
        expected["CustomCondition in a RULE — the control"] = emptyList()
        expected["RepetitionNotUnique in a RULE — the control"] = emptyList()
        // The SLOT decides the class, not the text: A12 answers the same predicate in an operation with
        // `MVK_UNEXPECTED_TOKEN` (`ComputationPreconditionLanguageFeatureLawsTest`), the grammar family's
        // class, so this family must not reach the alternative's other string member.
        expected["RepetitionNotUnique in the OPERATION — the control"] = listOf("MVK_UNEXPECTED_TOKEN")
        assertEquals(expected, actual, "which predicates a computation precondition may not use")
    }

    /**
     * An entity spelled like the predicate is a QUOTED name, and this family must not read it as a use.
     *
     * A checker-side claim, not a kernel one: nothing here says what A12 makes of the model as a whole, only
     * that a `'…'` token is not the keyword. The tokenizer already separates the two kinds for exactly this
     * reason, so a substring scan — the obvious implementation — is what this row refuses.
     */
    @Test
    fun aQuotedEntityNamedAfterThePredicateIsNotAUseOfIt() {
        val findings = check(ModelSource(model(precondition = "FieldFilled('RepetitionNotUnique')")))
            .findings.map { it.code }
        assertEquals(emptyList(), findings.filter { it == prohibited })
    }

    /** The finding names the member and the predicate, since the fix is to move or drop that conjunct. */
    @Test
    fun theFindingNamesTheMemberAndThePredicate() {
        val finding = check(
            ModelSource(model(precondition = "FieldFilled(Lines/Sku) And CustomCondition NotReverse")),
        ).findings.single { it.code == prohibited }
        assertEquals("/Basket/LineTotal", finding.at)
        assertEquals(true, finding.explain.contains("CustomCondition"), finding.explain)
        assertEquals(true, finding.explain.contains("precondition"), finding.explain)
    }

    /**
     * A basket model carrying either a computation or a rule over the same repeatable group.
     *
     * One builder for both, because the computation/rule split IS the comparison: the same condition text is
     * refused in one slot and ordinary in the other.
     */
    private fun model(
        precondition: String? = null,
        commonPrecondition: String? = null,
        condition: String? = null,
        operation: String = "1",
    ): String {
        val declaration = if (condition != null) {
            """{"type":"Rule","id":"r0","name":"LineTotal","Rule":{
                 "errorCondition":"$condition","errorEntityRelPath":"../Lines/Sku",
                 "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        } else {
            val common = commonPrecondition?.let { """"commonPrecondition":"$it",""" } ?: ""
            val guard = precondition?.let { """"precondition":"$it",""" } ?: ""
            """{"type":"Computation","id":"c0","name":"LineTotal","Computation":{
                 "computedFieldRelPath":"../Lines/Amount",$common
                 "computationAlternatives":[{$guard"operation":"$operation"}],
                 "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        }
        return """
            {"header":{"id":"basket","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Basket","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f0","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                $declaration
              ]}}
             ]}}}
        """.trimIndent()
    }
}
