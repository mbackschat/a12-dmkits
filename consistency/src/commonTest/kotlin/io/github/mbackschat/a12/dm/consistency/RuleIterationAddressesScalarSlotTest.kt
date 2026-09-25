package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `rulekit.validate.laws.RuleIterationAddressesScalarSlotLawsTest`: the level a rule
 * iterates addresses a scalar slot's read, whether the rule is declared inside the repetition or its error
 * field sits there ([KERNEL-FINDINGS KF477]).
 *
 * Every row mirrors one measured against the kernel on the same fields. The root rule's `MVK_NO_WILDCARD` is
 * the reference family's, not the gate's, so its row asserts that the gate stays out of it.
 */
class RuleIterationAddressesScalarSlotTest {

    /** A policy model whose one rule, placed in [ruleGroup], reports on [errorRelPath] when [condition] holds. */
    private fun codes(ruleGroup: String, errorRelPath: String, condition: String): List<String> {
        val rule = """
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorRelPath",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent()
        val atRoot = if (ruleGroup == ROOT) ",$rule" else ""
        val inClaims = if (ruleGroup == CLAIMS) ",$rule" else ""
        val inRiders = if (ruleGroup == RIDERS) ",$rule" else ""
        return check(
            ModelSource(
                """
                {"header":{"id":"policy-iterated-slot","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":20,"indexFieldName":"ClaimRef",
                     "elements":[
                      {"type":"Field","id":"f1","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f10","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                        "NumberType":{"maxFractionalDigits":2}}}},
                      {"type":"Field","id":"f2","name":"Memo","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f3","name":"Settled","Field":{"fieldType":{"type":"BooleanType"}}},
                      {"type":"Group","id":"g2","name":"Assessor","Group":{"elements":[
                        {"type":"Field","id":"f4","name":"AssessorName","Field":{"fieldType":{"type":"StringType"}}}
                      ]}}$inClaims
                    ]}},
                    {"type":"Group","id":"g4","name":"Riders","Group":{"repeatability":5,"elements":[
                      {"type":"Field","id":"f6","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f11","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                        "NumberType":{"maxFractionalDigits":2}}}},
                      {"type":"Field","id":"f7","name":"Memo","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f8","name":"Settled","Field":{"fieldType":{"type":"BooleanType"}}},
                      {"type":"Group","id":"g5","name":"Assessor","Group":{"elements":[
                        {"type":"Field","id":"f9","name":"AssessorName","Field":{"fieldType":{"type":"StringType"}}}
                      ]}}$inRiders
                    ]}},
                    {"type":"Group","id":"g3","name":"Notes","Group":{"repeatability":5,"elements":[
                      {"type":"Field","id":"f5","name":"Text","Field":{"fieldType":{"type":"StringType"}}}
                    ]}}$atRoot
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.justifiesRejection }.map { it.code }
    }

    @Test
    fun theLevelARuleIteratesAddressesTheRead() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        for ((gate, probe) in PROBES) {
            val (field, gateClass) = GATES.getValue(gate)
            actual["$gate — a root rule"] =
                codes(ROOT, "../Reference", "FieldFilled(Reference) And ${probe.with("Claims/$field")}")
                    .filter { it == gateClass }
            expected["$gate — a root rule"] = emptyList()
            actual["$gate — a rule declared inside Claims"] =
                codes(CLAIMS, "../ClaimRef", "FieldFilled(ClaimRef) And ${probe.with(field)}")
            expected["$gate — a rule declared inside Claims"] = listOf(gateClass)
            actual["$gate — a root rule whose error field is in Claims"] = codes(ROOT, "../Claims/ClaimRef",
                "FieldFilled(Claims/ClaimRef) And ${probe.with("Claims/$field")}")
            expected["$gate — a root rule whose error field is in Claims"] = listOf(gateClass)
        }
        assertEquals(expected, actual, "what a scalar slot names on a read the rule's iteration addresses")
    }

    @Test
    fun aKeyAndAFilterBodyAddressTheReadAsOnEveryOtherGate() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        for ((gate, probe) in PROBES) {
            val (field, gateClass) = GATES.getValue(gate)
            actual["$gate — a string key inside Claims"] =
                codes(CLAIMS, "../ClaimRef", "FieldFilled(ClaimRef) And ${probe.with("$field For \\\"X\\\"")}")
            // A key whose value is a GROUP is one neither the shared reader nor the key chain judges, so both key
            // rows of that gate are silent here where A12 names a class (DEV-API-GAPS DG97).
            val groupValued = gate == "field-operand kind"
            expected["$gate — a string key inside Claims"] = if (groupValued) emptyList() else listOf(gateClass)
            actual["$gate — a number literal key inside Claims"] =
                codes(CLAIMS, "../ClaimRef", "FieldFilled(ClaimRef) And ${probe.with("$field For 5")}")
            // A12 names the key's `MVK_INVALID_ENTITY` here, which this checker does not (DEV-API-GAPS DG98): the
            // row locks that the gate does not read past the key, and empty is that under-report, disclosed.
            expected["$gate — a number literal key inside Claims"] = emptyList()
            // The admission half of the key reader: `Riders` declares no index field, so its key is refused.
            actual["$gate — a string key inside Riders"] =
                codes(RIDERS, "../ClaimRef", "FieldFilled(ClaimRef) And ${probe.with("$field For \\\"X\\\"")}")
            expected["$gate — a string key inside Riders"] =
                if (groupValued) emptyList() else listOf("MVK_NO_INDEX_FIELD")
            actual["$gate — another level's filter body"] = codes(ROOT, "../Reference",
                "FieldFilled(Reference) And NumberOfFilledFields(Notes*/Text Having " +
                    "${probe.with("Claims/$field")}) > 0")
            expected["$gate — another level's filter body"] = listOf(gateClass)
            actual["$gate — the filtered level's own body"] = codes(ROOT, "../Reference",
                "FieldFilled(Reference) And NumberOfFilledFields(Claims*/Memo Having " +
                    "${probe.with("Claims/$field")}) > 0")
            expected["$gate — the filtered level's own body"] = listOf(gateClass)
        }
        assertEquals(expected, actual, "what a scalar slot names on a keyed read and on a filter-body read")
    }

    /** This probe with [operand] where its `%s` stands; `String.format` is JVM-only, and this is common code. */
    private fun String.with(operand: String): String = replace("%s", operand)

    private companion object {
        const val ROOT = "Policy"
        const val CLAIMS = "Claims"
        const val RIDERS = "Riders"

        /** Each gate's probe over one operand, written `%s`; the quote is JSON-escaped for the model text. */
        val PROBES = linkedMapOf(
            "field-operand kind" to "FieldFilled(%s)",
            "value-list subject" to "FieldValueIncludedInValueList(%s, \\\"x\\\")",
            "date-argument slot" to "AddDays(%s, 1) < Today",
            "range operand" to "RangeAsString(%s, 1, 2) == \\\"a\\\"",
            "value-as-date operand" to "ValueAsDate(%s, FirstDay) > \\\"01.01.2020\\\"",
        )

        /** The field of `Claims` each gate is probed on, and the class the kernel names there. */
        val GATES = mapOf(
            "field-operand kind" to ("Assessor" to "MVK_NO_GROUPS_ALLOWED"),
            "value-list subject" to ("Settled" to "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"),
            "date-argument slot" to ("Memo" to "MVK_NO_DATE"),
            "range operand" to ("Amount" to "MVK_INVALID_TYPE"),
            "value-as-date operand" to ("Memo" to "MVK_INVALID_DATE_TYPE_FOR_OP"),
        )
    }
}
