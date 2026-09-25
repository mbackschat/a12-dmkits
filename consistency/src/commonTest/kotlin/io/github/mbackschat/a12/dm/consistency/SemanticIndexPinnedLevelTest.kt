package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `rulekit.validate.laws.SemanticIndexPinnedLevelLawsTest`: a semantic-index key
 * addresses the level of the group that declares the index, and only that one ([KERNEL-FINDINGS KF478]).
 *
 * Every row mirrors one measured against the kernel. Four of them stay SILENT where A12 names a class, and
 * each is a disclosed under-report rather than agreement: the doubly indexed path, whose class this checker
 * does not name on a key (DEV-API-GAPS DG100), and three reads the key leaves unaddressed, whose
 * `MVK_NO_WILDCARD` the operand scope family withholds on any text naming an index (DG99). What those rows
 * lock is that no operand gate names its own class there, which it did while the key pinned "the one level
 * crossed".
 */
class SemanticIndexPinnedLevelTest {

    /** The pinned-level model: the root and `Cover` declare an index field only when asked to. */
    private fun codes(
        ruleGroup: String,
        errorRelPath: String,
        operand: String,
        rootIndexed: Boolean = false,
        coverIndexed: Boolean = false,
    ): List<String> {
        val errorLeaf = errorRelPath.removePrefix("../")
        val rule = """
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled($errorLeaf) And Sum($operand) > 0","errorEntityRelPath":"$errorRelPath",
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
            "the index group IS the one repetition crossed" to codes(ROOT, "../Reference", "Claims/Amount $key"),
            "a second indexed ancestor: the root" to
                codes(ROOT, "../Reference", "Claims/Amount $key", rootIndexed = true),
            "no index group on the path" to codes(ROOT, "../Reference", "Cover/Items/Amount $key"),
            "the index group is the ROOT, above the repetition" to
                codes(ROOT, "../Reference", "Cover/Items/Amount $key", rootIndexed = true),
            "the index group is a FIXED group above the repetition" to
                codes(ROOT, "../Reference", "Cover/Items/Amount $key", coverIndexed = true),
            "an indexed repetition below an unindexed one, from a root rule" to
                codes(ROOT, "../Reference", "Riders/Legs/Amount $key"),
            "the same from a rule the unindexed repetition iterates" to
                codes(RIDERS, "../RiderRef", "Legs/Amount $key"),
            "the same unkeyed" to codes(RIDERS, "../RiderRef", "Legs/Amount"),
        )
        val expected = linkedMapOf(
            "the index group IS the one repetition crossed" to listOf("MVK_PARAMSIZE_INVALIDN"),
            "a second indexed ancestor: the root" to emptyList(),
            "no index group on the path" to listOf("MVK_NO_INDEX_FIELD"),
            "the index group is the ROOT, above the repetition" to emptyList(),
            "the index group is a FIXED group above the repetition" to emptyList(),
            "an indexed repetition below an unindexed one, from a root rule" to emptyList(),
            "the same from a rule the unindexed repetition iterates" to listOf("MVK_PARAMSIZE_INVALIDN"),
            "the same unkeyed" to listOf("MVK_NO_WILDCARD"),
        )
        assertEquals<Map<String, List<String>>>(expected, actual, "which level a semantic-index key addresses")
    }

    private companion object {
        const val ROOT = "Policy"
        const val RIDERS = "Riders"
    }
}
