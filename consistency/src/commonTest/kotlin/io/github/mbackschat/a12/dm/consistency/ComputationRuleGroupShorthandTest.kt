package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + JS mirror of `adapter.laws.ComputationMessageCarrierAgreementTest`'s group rows (KERNEL-FINDINGS KF563): a
 * computation in a root group refuses the rule-group shorthand with the rule's class, and one in a child group
 * admits it.
 */
class ComputationRuleGroupShorthandTest {

    @Test
    fun theShorthandIsRefusedInARootGroupOnly() {
        assertEquals(mapOf(
            "root group" to listOf("RULEGROUP_IMMEDIATELY_BELOW_ROOT"),
            "child group" to emptyList(),
        ), mapOf(
            "root group" to refused(rootComputation = true),
            "child group" to refused(rootComputation = false),
        ))
    }

    private fun refused(rootComputation: Boolean): List<String> =
        checkRead(ModelSource(model(rootComputation))).findings.filter { it.justifiesRejection }.map { it.code }

    /** An insurance quote: a premium on the root, quote lines with a gross amount. */
    private fun model(rootComputation: Boolean): String {
        val computation = if (rootComputation)
            computation("PremiumComp", "../Premium", "[Base] + 1")
        else computation("GrossComp", "../Gross", "[Amount] * 2")
        return """
            {"header":{"modelVersion":"29.4.0","id":"quote-rule-group-shorthand","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Quote","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Premium","Field":{"fieldType":{"type":"NumberType","maxFractionalDigits":0}}},
                {"type":"Field","id":"f1","name":"Base","Field":{"fieldType":{"type":"NumberType","maxFractionalDigits":0}}},
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"elements":[
                  {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType","maxFractionalDigits":0}}},
                  {"type":"Field","id":"f3","name":"Gross","Field":{"fieldType":{"type":"NumberType","maxFractionalDigits":0}}}
                  ${if (rootComputation) "" else ",$computation"}
                ]}}
                ${if (rootComputation) ",$computation" else ""}
              ]}}
             ]}}}
        """.trimIndent()
    }

    private fun computation(name: String, target: String, operation: String) = """
        {"type":"Computation","id":"c0","name":"$name","Computation":{
          "computedFieldRelPath":"$target","computationAlternatives":[{"operation":"$operation"}],
          "errorMessage":[{"locale":"en_US","text":"See ${'$'}#RuleGroup${'$'}."}]}}
    """
}
