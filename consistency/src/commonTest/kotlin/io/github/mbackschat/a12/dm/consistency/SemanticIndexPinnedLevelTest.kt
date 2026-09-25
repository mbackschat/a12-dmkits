package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `rulekit.validate.laws.SemanticIndexPinnedLevelLawsTest`: a semantic-index key
 * addresses the level of the group that declares the index, and only that one ([KERNEL-FINDINGS KF478]).
 *
 * Every row mirrors one measured against the kernel, and names the class A12 names.
 */
class SemanticIndexPinnedLevelTest {

    /**
     * The pinned-level model with one rule reporting on [errorRelPath] when [probe] holds beside its own target;
     * the root and `Cover` declare an index field only when asked to.
     */
    private fun codes(
        ruleGroup: String,
        errorRelPath: String,
        probe: String,
        rootIndexed: Boolean = false,
        coverIndexed: Boolean = false,
    ): List<String> {
        val errorLeaf = errorRelPath.removePrefix("../")
        val rule = """
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled($errorLeaf) And $probe","errorEntityRelPath":"$errorRelPath",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent()
        val atRoot = if (ruleGroup == ROOT) ",$rule" else ""
        val inRiders = if (ruleGroup == RIDERS) ",$rule" else ""
        val rootIndex = if (rootIndexed) "\"indexFieldName\":\"Reference\"," else ""
        val coverIndex = if (coverIndexed) "\"indexFieldName\":\"CoverRef\"," else ""
        return check(
            ModelSource(
                """
                {"header":{"id":"policy-pinned-level","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{${rootIndex}"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":20,"indexFieldName":"ClaimRef",
                     "elements":[
                      {"type":"Field","id":"f1","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                        "NumberType":{"maxFractionalDigits":2}}}}
                    ]}},
                    {"type":"Group","id":"g2","name":"Riders","Group":{"repeatability":5,"elements":[
                      {"type":"Field","id":"f3","name":"RiderRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Group","id":"g3","name":"Legs","Group":{"repeatability":5,"indexFieldName":"LegRef",
                       "elements":[
                        {"type":"Field","id":"f4","name":"LegRef","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Field","id":"f5","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                          "NumberType":{"maxFractionalDigits":2}}}}
                      ]}}$inRiders
                    ]}},
                    {"type":"Group","id":"g4","name":"Cover","Group":{${coverIndex}"elements":[
                      {"type":"Field","id":"f6","name":"CoverRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Group","id":"g5","name":"Items","Group":{"repeatability":5,"elements":[
                        {"type":"Field","id":"f7","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                          "NumberType":{"maxFractionalDigits":2}}}}
                      ]}}
                    ]}}$atRoot
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.justifiesRejection }.map { it.code }
    }

    @Test
    fun theKeyAddressesTheIndexGroupsLevelAndNoOther() {
        val key = "For \\\"X\\\""
        val actual = linkedMapOf(
            "the index group IS the one repetition crossed" to codes(ROOT, "../Reference", sum("Claims/Amount $key")),
            "a second indexed ancestor: the root" to
                codes(ROOT, "../Reference", sum("Claims/Amount $key"), rootIndexed = true),
            "no index group on the path" to codes(ROOT, "../Reference", sum("Cover/Items/Amount $key")),
            "the index group is the ROOT, above the repetition" to
                codes(ROOT, "../Reference", sum("Cover/Items/Amount $key"), rootIndexed = true),
            "the index group is a FIXED group above the repetition" to
                codes(ROOT, "../Reference", sum("Cover/Items/Amount $key"), coverIndexed = true),
            "an indexed repetition below an unindexed one, from a root rule" to
                codes(ROOT, "../Reference", sum("Riders/Legs/Amount $key")),
            "the same from a rule the unindexed repetition iterates" to
                codes(RIDERS, "../RiderRef", sum("Legs/Amount $key")),
            "the same unkeyed" to codes(RIDERS, "../RiderRef", sum("Legs/Amount")),
        )
        val expected = linkedMapOf(
            "the index group IS the one repetition crossed" to listOf("MVK_PARAMSIZE_INVALIDN"),
            "a second indexed ancestor: the root" to listOf("MVK_MULTIPLE_INDEX_FIELDS"),
            "no index group on the path" to listOf("MVK_NO_INDEX_FIELD"),
            "the index group is the ROOT, above the repetition" to listOf("MVK_NO_WILDCARD"),
            "the index group is a FIXED group above the repetition" to listOf("MVK_NO_WILDCARD"),
            "an indexed repetition below an unindexed one, from a root rule" to listOf("MVK_NO_WILDCARD"),
            "the same from a rule the unindexed repetition iterates" to listOf("MVK_PARAMSIZE_INVALIDN"),
            "the same unkeyed" to listOf("MVK_NO_WILDCARD"),
        )
        assertEquals<Map<String, List<String>>>(expected, actual, "which level a semantic-index key addresses")
    }

    @Test
    fun theReadsAroundAKeyAnswerInTextOrder() {
        val keyed = sum("Claims/Amount For \\\"X\\\"")
        val unaddressed = "[Riders/RiderRef] == \\\"a\\\""
        val actual = linkedMapOf(
            "an unaddressed read before a keyed operand" to codes(ROOT, "../Reference", "$unaddressed And $keyed"),
            "the keyed operand first" to codes(ROOT, "../Reference", "$keyed And $unaddressed"),
            "a key that is itself an unaddressed read" to
                codes(ROOT, "../Reference", sum("Claims/Amount For Riders/RiderRef")),
        )
        val expected = linkedMapOf(
            "an unaddressed read before a keyed operand" to listOf("MVK_NO_WILDCARD"),
            "the keyed operand first" to listOf("MVK_PARAMSIZE_INVALIDN"),
            "a key that is itself an unaddressed read" to listOf("MVK_SEMANTIC_INDEX_INVALID_ITER"),
        )
        assertEquals<Map<String, List<String>>>(expected, actual, "which class a text holding a key names first")
    }

    private fun sum(operand: String): String = "Sum($operand) > 0"

    private companion object {
        const val ROOT = "Policy"
        const val RIDERS = "Riders"
    }
}
