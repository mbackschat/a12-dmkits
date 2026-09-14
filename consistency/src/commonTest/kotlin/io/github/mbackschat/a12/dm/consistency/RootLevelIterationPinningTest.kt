package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a parse-time class withholds the iteration classes drawn AFTER the parse.
 *
 * `IterationChecker` runs over the finished reference set, so every class it draws needs a parse that
 * completed. `MVK_ERROR_FIELD_NOT_REFERENCED` — from the same method — was listed as post-parse from the
 * start; its two siblings were not, and an index field on the ROOT group is where that shows: A12 draws
 * `MVK_MULTIPLE_INDEX_FIELDS` during the parse and nothing else, while this checker named the iteration
 * class beside it (`rulekit.validate.laws.RootLevelIterationPinningLawsTest`).
 *
 * The rows are the WHOLE code list rather than one family's, because "one class, not two" is the claim.
 */
class RootLevelIterationPinningTest {

    @Test
    fun anIndexFieldOnTheRootWithholdsTheIterationClass() {
        assertEquals(
            listOf("MVK_MULTIPLE_INDEX_FIELDS"),
            codes(model(indexedRoot = true)),
            "A12 answers the parse-time class alone, so the post-parse sibling must not ride beside it",
        )
    }

    /** The control: the same model minus the root's index field, where the iteration class IS A12's answer. */
    @Test
    fun withoutThatIndexFieldTheIterationClassIsTheAnswer() {
        assertEquals(
            listOf("MVK_ERROR_FIELDGROUP_ITERATION5_INVALID"),
            codes(model(indexedRoot = false)),
            "the two models differ in one declaration, so the row above measures the index field",
        )
    }

    private fun codes(json: String): List<String> = check(ModelSource(json)).findings.map { it.code }

    /** An insurance claims register mirroring the laws test's carrier. */
    private fun model(indexedRoot: Boolean): String {
        val rootKey = if (indexedRoot) ""","indexFieldName":"CaseNo"""" else ""
        return """
            {"header":{"id":"register","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Register","Group":{"repeatability":5$rootKey,"elements":[
                {"type":"Field","id":"f0","name":"CaseNo","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Received","Group":{"repeatability":10,
                  "indexFieldName":"LineNo","elements":[
                  {"type":"Field","id":"f2","name":"LineNo","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f3","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f4","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Rule","id":"r0","name":"Probe","Rule":{
                    "errorCondition":"GroupFilled(/Register) And [/Register/Expected/Quantity] > 0",
                    "errorEntityRelPath":"../Note",
                    "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
                ]}},
                {"type":"Group","id":"g2","name":"Expected","Group":{"repeatability":10,
                  "indexFieldName":"LineNo","elements":[
                  {"type":"Field","id":"f5","name":"LineNo","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}}
                ]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
