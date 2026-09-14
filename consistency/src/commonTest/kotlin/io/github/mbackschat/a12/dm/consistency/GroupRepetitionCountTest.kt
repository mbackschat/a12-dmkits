package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's cap on how often a group may repeat.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.GroupIndexAndSortReferenceLawsTest`. The two carriers are the point: a root group
 * and a nested one are capped an order of magnitude apart, so the same count is legal in one place and not
 * in the other.
 */
class GroupRepetitionCountTest {

    private val tooBig = "MVK_MAX_TOO_BIG"

    /** A catalog whose root group carries [root] and whose nested `Pallets` carries [nested]. */
    private fun codes(root: String = "", nested: String = ""): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{$root"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Pallets","Group":{$nested"elements":[
                  {"type":"Field","id":"f1","name":"PalletCode","Field":{"fieldType":{
                    "type":"StringType"}}}
                ]}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    @Test
    fun theCapDependsOnTheCarrier() {
        val actual = mutableMapOf<String, List<String>>()
        actual["neither group declares a count — the control"] = codes()
        actual["a root group at its own cap"] = codes(root = """"repeatability":999999999,""")
        actual["a root group one past it"] = codes(root = """"repeatability":1000000000,""")
        actual["a NESTED group at the root's cap"] = codes(nested = """"repeatability":1000000000,""")
        actual["a nested group at its own cap"] = codes(nested = """"repeatability":1999999999,""")
        actual["a nested group one past it"] = codes(nested = """"repeatability":2000000000,""")
        actual["a nested group at the signed 32-bit ceiling"] =
            codes(nested = """"repeatability":2147483647,""")
        actual["a nested group one past that ceiling"] = codes(nested = """"repeatability":2147483648,""")

        val expected = mutableMapOf<String, List<String>>()
        expected["neither group declares a count — the control"] = emptyList()
        expected["a root group at its own cap"] = emptyList()
        expected["a root group one past it"] = listOf(tooBig)
        // The row that fails if the two carriers are ever collapsed onto one bound.
        expected["a NESTED group at the root's cap"] = emptyList()
        expected["a nested group at its own cap"] = emptyList()
        expected["a nested group one past it"] = listOf(tooBig)
        expected["a nested group at the signed 32-bit ceiling"] = listOf(tooBig)
        // Past the ceiling A12 never reads the source, so naming its class would claim a verdict it never
        // reached — the unbuildable tier is the honest report.
        expected["a nested group one past that ceiling"] = listOf(CK_MODEL_NOT_BUILDABLE)
        assertEquals(expected, actual, "a group's repetition count against A12's cap")
    }
}
