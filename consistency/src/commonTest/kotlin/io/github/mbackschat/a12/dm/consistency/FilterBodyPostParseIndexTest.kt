package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The filter index gate's post-parse site on both targets: a `$`-marked keyed read in a `Having` body.
 *
 * Every row mirrors one of `adapter.laws.CheckerFilterBodyAgreementTest`, which holds A12's class beside the
 * checker's ([KERNEL-FINDINGS KF487]). The rule iterates `Claims`, so the marked read is at the rule's coordinates
 * and only its index is refused. The last row is the discriminating one: a parse-time class later in the text
 * answers, which is right only because report assembly files this instance as raised after the parse.
 */
class FilterBodyPostParseIndexTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"claims-policy","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":5,"elements":[
                  {"type":"Field","id":"f1","name":"Notes","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Parties","Group":{"repeatability":4,"elements":[
                  {"type":"Field","id":"f2","name":"Name","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f3","name":"Count","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Group","id":"g3","name":"Orders","Group":{"repeatability":3,"indexFieldName":"OrderNo",
                  "elements":[
                  {"type":"Field","id":"f4","name":"OrderNo","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f5","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"${"FieldFilled(Claims/Notes) And $condition".replace("\"", "\\\"")}",
                  "errorEntityRelPath":"../Claims/Notes","errorCode":"C1",
                  "errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.filter { it.justifiesRejection }.map { it.code }

    @Test
    fun aMarkedKeyedReadIsRefusedOnceTheParseIsDone() {
        assertEquals(listOf("MVK_SEMANTIC_INDEX_NOT_ALLOWED"),
            codes("Sum(Parties*/Count Having [Parties/Name] == [\$Orders/Note For \"A\"]) > 0"))
    }

    @Test
    fun aParseTimeClassAnswersAheadOfIt() {
        assertEquals(listOf("MVK_NO_ITERATION_FOR_WILDCARD"),
            codes("Sum(Parties*/Count Having [\$Orders/Note For \"A\"] == \"x\") > 0"))
        assertEquals(listOf("MVK_INVALID_ITERATION_IN_FILTER_CONDITION"),
            codes("Sum(Parties*/Count Having [Parties/Name] == [\$Orders/Note For \"A\"]) > 0 And " +
                "Sum(Parties*/Count Having [Orders/Note] == \"y\") > 0"))
    }
}
