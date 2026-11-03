package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's reserved final field-name segment.
 *
 * Mirrors `rulekit.validate.laws.FieldNameReservedSuffixLawsTest` on the same warehouse shape. Two rows carry
 * the risk, and both are near-misses of the reserved word rather than illegal names: `value` alone is
 * ADMITTED, because A12's pattern makes the dotted group optional and never asks the reserved-word test; and
 * the segment counts only in FINAL position, so `Slot.value.List` is an ordinary dotted name.
 *
 * The grid reports this family's class alone. The dot that has to precede the segment draws
 * `MVK_NAME_INVALID_DOT_COLON_A12_DV` from `ELEMENT_NAME_ALPHABET`, which is a separate family's claim and is
 * locked in its own mirror.
 */
class FieldNameReservedSegmentTest {

    private val reserved = "MVK_NAME_INVALID_A12_DV"

    @Test
    fun onlyAFinalReservedSegmentDrawsIt() {
        val actual = mutableMapOf<String, List<String>>()
        actual["Slot — the liveness control"] = codes("Slot")
        actual["value — the reserved word with no dot before it"] = codes("value")
        actual["Slot.List — a dotted name whose last segment is ordinary"] = codes("Slot.List")
        actual["Slot.value — the reserved final segment"] = codes("Slot.value")
        actual["Slot.VALUE — the same segment, upper case"] = codes("Slot.VALUE")
        actual["Slot.values — one letter past it"] = codes("Slot.values")
        actual["Slot.value.List — reserved in the MIDDLE"] = codes("Slot.value.List")
        actual["Slot.List.value — reserved at the END of a longer name"] = codes("Slot.List.value")
        actual["Slot-value — a name outside A12's pattern entirely"] = codes("Slot-value")

        val expected = mutableMapOf<String, List<String>>()
        expected["Slot — the liveness control"] = emptyList()
        expected["value — the reserved word with no dot before it"] = emptyList()
        expected["Slot.List — a dotted name whose last segment is ordinary"] = emptyList()
        expected["Slot.value — the reserved final segment"] = listOf(reserved)
        expected["Slot.VALUE — the same segment, upper case"] = listOf(reserved)
        expected["Slot.values — one letter past it"] = emptyList()
        expected["Slot.value.List — reserved in the MIDDLE"] = emptyList()
        expected["Slot.List.value — reserved at the END of a longer name"] = listOf(reserved)
        // The pattern arm is A12's too and it is pre-empted on every character, so a family reproducing it
        // would name a class A12 does not use — the alphabet family answers this name instead.
        expected["Slot-value — a name outside A12's pattern entirely"] = emptyList()
        assertEquals(expected, actual, "which field names the reserved-segment gate refuses")
    }

    /** A GROUP carrying the same name is not this gate's business — the field carrier is. */
    @Test
    fun theGateIsTheFieldCarriersAlone() {
        assertEquals(
            emptyList(),
            checkRead(ModelSource(binWithAGroupNamed("Slot.value"))).findings.filter { it.code == reserved },
        )
    }

    private fun codes(fieldName: String): List<String> =
        checkRead(ModelSource(bin(fieldName))).findings.filter { it.code == reserved }.map { it.code }

    private fun bin(fieldName: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"warehouse-bin","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Bin","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"$fieldName","Field":{"fieldType":{"type":"StringType"}}}
          ]}}
         ]}}}
    """.trimIndent()

    private fun binWithAGroupNamed(groupName: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"warehouse-bin","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Bin","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"$groupName","Group":{"elements":[
              {"type":"Field","id":"f1","name":"Slot","Field":{"fieldType":{"type":"StringType"}}}
            ]}}
          ]}}
         ]}}}
    """.trimIndent()
}
