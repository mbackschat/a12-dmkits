package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a condition stands only where A12's grammar takes one".
 *
 * Mirrors `rulekit.validate.laws.WhereAConditionMayStandLawsTest` row for row. Each row moves one condition
 * construct — a connective, a comparison, a predicate call, a parenthesis — into one position the grammar
 * gives an operand, so every refused row differs from an admitted control in exactly that construct.
 */
class WhereAConditionMayStandTest {

    @Test
    fun anOperatorsArgumentIsNeverACondition() {
        val actual = linkedMapOf(
            "an operation argument — the control" to rule(READS_IT + "Max([Score], 2) > 0"),
            "a connective in a field operand" to rule("FieldFilled(Reference And FieldFilled(Reference))"),
            "a comparison in a field operand" to rule(READS_IT + "FieldFilled(Score > 3)"),
            "a predicate call as a field operand" to rule(READS_IT + "FieldFilled(FieldFilled(Score))"),
            "a comparison in an operation argument" to rule(READS_IT + "Max([Score] > 1, 2) > 0"),
            "a predicate call in an operation argument" to rule(READS_IT + "Abs(FieldFilled(Score)) > 0"),
            "a parenthesised field operand" to rule(READS_IT + "Sum((Claims*/Amount)) > 0"),
            "a parenthesised condition as an argument" to rule(READS_IT + "Max(([Score] > 1), 2) > 0"),
        )
        val expected = linkedMapOf(
            "an operation argument — the control" to ADMITTED,
            "a connective in a field operand" to UNEXPECTED,
            "a comparison in a field operand" to UNEXPECTED,
            "a predicate call as a field operand" to UNEXPECTED,
            "a comparison in an operation argument" to UNEXPECTED,
            "a predicate call in an operation argument" to UNEXPECTED,
            "a parenthesised field operand" to UNEXPECTED,
            "a parenthesised condition as an argument" to UNEXPECTED,
        )
        assertEquals(expected, actual, "what an operator's argument may be")
    }

    @Test
    fun anOperationGroupsWithBracesAndHoldsNoCondition() {
        val actual = linkedMapOf(
            "a braced operation — the control" to rule(READS_IT + "[Score] > {[Score] + 1}"),
            "a comparison in braces" to rule(READS_IT + "[Score] > {[Score] > 1}"),
            "a parenthesised operation as a side" to rule(READS_IT + "[Score] > ([Score] + 1)"),
            "a parenthesised condition as a side" to rule(READS_IT + "[Score] > ([Score] > 1)"),
            "a predicate call as a side" to rule(READS_IT + "[Score] > FieldFilled(Score)"),
            "a parenthesised condition — the control" to rule(READS_IT + "([Score] > 1 Or [Score] < 0)"),
            "a parenthesised operation leading a comparison" to rule(READS_IT + "([Score] + 1) > 3"),
            "a computation, a braced operation — the control" to computation("{[Score] + 1}"),
            "a computation, a parenthesised operation" to computation("([Score] + 1)"),
            "a computation, a predicate call" to computation("FieldFilled(Score)"),
            "a computation, a comparison" to computation("[Score] > 1"),
        )
        val expected = linkedMapOf(
            "a braced operation — the control" to ADMITTED,
            "a comparison in braces" to UNEXPECTED,
            "a parenthesised operation as a side" to UNEXPECTED,
            "a parenthesised condition as a side" to UNEXPECTED,
            "a predicate call as a side" to UNEXPECTED,
            "a parenthesised condition — the control" to ADMITTED,
            "a parenthesised operation leading a comparison" to UNEXPECTED,
            "a computation, a braced operation — the control" to ADMITTED,
            "a computation, a parenthesised operation" to UNEXPECTED,
            "a computation, a predicate call" to UNEXPECTED,
            "a computation, a comparison" to UNEXPECTED,
        )
        assertEquals(expected, actual, "what an operation may hold")
    }

    @Test
    fun onlyAFilterClauseTakesACondition() {
        fun sum(body: String) = rule(READS_IT + "Sum(Claims*/Amount Having $body) > 0")
        fun keyed(key: String) = rule(READS_IT + "[/Policy/Payouts/Total For $key] > 0")
        val actual = linkedMapOf(
            "a filter body — the control" to sum("[Claims/Amount] > 0"),
            "a filter body in parentheses" to sum("([Claims/Amount] > 0)"),
            "a filter body that is a predicate call" to sum("FieldFilled(Claims/Amount)"),
            "a filter body that is an operand alone" to sum("[Claims/Amount]"),
            "a key — the control" to keyed(P1),
            "a comparison in a key" to keyed("$P1 > 3"),
            "a scope — the control" to rule("RepetitionNotUnique(Claims/ClaimNo @From Claims)", "../Claims/ClaimNo"),
            "a comparison in a scope" to
                rule("RepetitionNotUnique(Claims/ClaimNo @From Claims > 3)", "../Claims/ClaimNo"),
        )
        val expected = linkedMapOf(
            "a filter body — the control" to ADMITTED,
            "a filter body in parentheses" to ADMITTED,
            "a filter body that is a predicate call" to ADMITTED,
            "a filter body that is an operand alone" to UNEXPECTED,
            "a key — the control" to ADMITTED,
            "a comparison in a key" to UNEXPECTED,
            "a scope — the control" to ADMITTED,
            "a comparison in a scope" to UNEXPECTED,
        )
        assertEquals(expected, actual, "which clause takes a condition")
    }

    @Test
    fun aFilterBodysComparisonIsJudgedThereToo() {
        val actual = linkedMapOf(
            "a text ordered against a number — the control" to rule(READS_IT + "[Reference] > 5"),
            "the same inside a filter body" to rule(READS_IT + "Sum(Claims*/Amount Having [Claims/ClaimNo] > 5) > 0"),
            "the same over a field outside the filtered group" to
                rule(READS_IT + "Sum(Claims*/Amount Having [Reference] > 5) > 0"),
            "its sound twin" to rule(READS_IT + "Sum(Claims*/Amount Having [Reference] == $X) > 0"),
            "the control's comparison after a filter body" to
                rule(READS_IT + "Sum(Claims*/Amount Having [Claims/Amount] > 0) > 0 And [Reference] > 5"),
        )
        val expected = linkedMapOf(
            "a text ordered against a number — the control" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
            // Typed whatever repetition it crosses: what a body reads is the filter chain's question, asked
            // only once the whole spec is read.
            "the same inside a filter body" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
            // Raised at the comparison, ahead of the iteration class the sound twin draws at the call's end.
            "the same over a field outside the filtered group" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
            "its sound twin" to listOf("MVK_NO_ITERATION_FOR_WILDCARD"),
            // Leaving a filter body hands the comparisons after it to the families again.
            "the control's comparison after a filter body" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
        )
        assertEquals(expected, actual, "where a comparison is judged")
    }

    private fun rule(condition: String, errorTarget: String = "../Reference"): List<String> = codes(
        """{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorTarget",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}""",
    )

    private fun computation(operation: String): List<String> = codes(
        """{"type":"Computation","id":"c0","name":"Probe","Computation":{
              "computedFieldRelPath":"../Bonus","computationAlternatives":[{"operation":"$operation"}],
              "errorMessage":[{"locale":"en_US","text":"see it"}]}}""",
    )

    private fun codes(declaration: String): List<String> = checkRead(ModelSource(model(declaration))).findings
        .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Policy/Probe" }
        .map { it.code }.distinct().sorted()

    /** The claims policy of the laws test. */
    private fun model(declaration: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"claims-policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Score","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f2","name":"Bonus","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":5,"elements":[
              {"type":"Field","id":"f3","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f4","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Payouts","Group":{"repeatability":5,"indexFieldName":"PayoutNo",
              "elements":[
              {"type":"Field","id":"f5","name":"PayoutNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f6","name":"Total","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            $declaration
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        /** Names the error field, so an admitted control is admitted rather than refused for not reading it. */
        const val READS_IT = "FieldFilled(Reference) And "

        /** A key as the model text carries it: the condition is interpolated into JSON as written. */
        const val P1 = "\\\"P1\\\""

        const val X = "\\\"x\\\""

        val ADMITTED = listOf<String>()
        val UNEXPECTED = listOf("MVK_UNEXPECTED_TOKEN")
    }
}
