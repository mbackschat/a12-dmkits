package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the `$index(…)$` terminal's ARGUMENT gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.RuleMessageIndexTerminalLawsTest`. The SILENT rows carry the risk and there are two
 * kinds: a shape that passes this gate and fails a LATER one this family does not reproduce, and a spelling
 * the lexer refuses before any gate runs.
 */
class ErrorTextIndexTerminalTest {

    @Test
    fun whichArgumentTheIndexTerminalTakes() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the index field of the error field's own group"] = reporting("row \$index(ClaimNo).value\$")
        actual["a non-index field of the same group"] = reporting("row \$index(Payout).value\$")
        actual["a field outside the group"] = reporting("row \$index(Reference).value\$")
        actual["a name no field carries"] = reporting("row \$index(Nope).value\$")
        actual["a name no field carries, and NO suffix either"] = reporting("row \$index(Nope)\$")
        actual["the index field spelled as a PATH"] = reporting("row \$index(/Policy/Claims/ClaimNo).value\$")
        actual["the index field with the suffix OMITTED"] = reporting("row \$index(ClaimNo)\$")
        actual["the terminal CAPITALISED"] = reporting("row \$Index(Nope).value\$")
        actual["no parameter at all — the control"] = reporting("row")

        val refused = listOf("INVALID_INDEX_FIELD")
        val expected = mutableMapOf<String, List<String>>()
        expected["the index field of the error field's own group"] = emptyList()
        expected["a non-index field of the same group"] = refused
        expected["a field outside the group"] = refused
        expected["a name no field carries"] = refused
        // The ARGUMENT gate is first and the missing-suffix gate is last, so this draws this class and not
        // `INVALID_INDEX_FIELD_PARAMETER` — the row that makes reporting the first gate alone sound.
        expected["a name no field carries, and NO suffix either"] = refused
        // Matched by SHORT NAME, so the path spelling names nothing — A12 refuses it and so does this.
        expected["the index field spelled as a PATH"] = refused
        // A12 answers `INVALID_INDEX_FIELD_PARAMETER` here, from the LAST of the five gates. The argument is
        // fine, so this family is correctly silent rather than naming its own class.
        expected["the index field with the suffix OMITTED"] = emptyList()
        // `$Index(…)$` is not this terminal at all: the lexer refuses it with `MVK_UNEXPECTED_TOKEN` before
        // any gate runs, and the condition-syntax family owns that.
        expected["the terminal CAPITALISED"] = emptyList()
        expected["no parameter at all — the control"] = emptyList()
        assertEquals(expected, actual, "which argument the index terminal takes")
    }

    /** The finding names what the error entity's path DOES offer, since that is the repair. */
    @Test
    fun theFindingNamesTheIndexFieldsThatPathOffers() {
        val finding = check(ModelSource(model("row \$index(Nope).value\$")))
            .findings.single { it.code == "INVALID_INDEX_FIELD" }
        assertEquals("/Policy/Claims/ClaimRule", finding.at)
        assertEquals(true, finding.explain.contains("ClaimNo"), finding.explain)
    }

    /** An insurance claims desk mirroring the laws test's carrier. */
    private fun reporting(text: String): List<String> =
        check(ModelSource(model(text))).findings.filter { it.code == "INVALID_INDEX_FIELD" }.map { it.code }

    private fun model(text: String): String = """
        {"header":{"id":"policy-index-terminal","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":20,
              "indexFieldName":"ClaimNo","elements":[
              {"type":"Field","id":"f1","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f2","name":"Payout","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Rule","id":"r0","name":"ClaimRule","Rule":{
                "errorCondition":"FieldFilled(Payout)","errorEntityRelPath":"../Payout",
                "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"$text"}]}}
            ]}}
          ]}}
         ]}}}
    """.trimIndent()
}
