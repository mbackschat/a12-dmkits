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

    private val invalid = "MVK_MAX_VALUES_INVALID"

    /** A catalog whose root group carries [root] and whose nested `Pallets` carries [nested]. */
    private fun codes(root: String = "", nested: String = ""): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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

    /**
     * The FLOOR, which this family did not hold and a stored model can reach.
     *
     * A12's side of these rows is [KERNEL-FINDINGS KF221], locked by
     * `adapter.laws.GroupRepeatabilityDomainLawsTest`: the domain is any integer `> 0`, and `0`, `null` and
     * `-1` draw `MVK_MAX_VALUES_INVALID`. The two boundary cells that finding did not carry — the signed
     * 32-bit floor, and one below it, which is refused as malformed input exactly as one above the ceiling
     * is — were measured on 2026-09-16 and extend it there.
     *
     * Both carriers are walked for the same reason the cap rows do it: the ceiling's bound differs per
     * carrier, so a floor asserted on one carrier alone would leave the other untested.
     */
    @Test
    fun aNonPositiveCountIsInvalidOnEitherCarrier() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the minimum A12 admits"] = codes(nested = """"repeatability":1,""")
        actual["a nested group at zero"] = codes(nested = """"repeatability":0,""")
        actual["a nested group at minus one"] = codes(nested = """"repeatability":-1,""")
        actual["a ROOT group at zero"] = codes(root = """"repeatability":0,""")
        actual["a nested group at the signed 32-bit floor"] =
            codes(nested = """"repeatability":-2147483648,""")
        actual["a nested group one below that floor"] = codes(nested = """"repeatability":-2147483649,""")
        actual["an explicit null count"] = codes(nested = """"repeatability":null,""")
        actual["the count as TEXT, which A12 coerces"] = codes(nested = """"repeatability":"3",""")

        // Each refused group carries the class, the member's range sentence, and the class once more for every
        // element beneath it — A12 derives an element's repetition from its ancestors (KERNEL-FINDINGS KF590).
        val notPositive = MemberRangeViolation.NOT_POSITIVE.code
        val signed = MemberRangeViolation.SIGNED.code
        val expected = mutableMapOf<String, List<String>>()
        expected["the minimum A12 admits"] = emptyList()
        expected["a nested group at zero"] = listOf(invalid, notPositive, invalid)
        expected["a nested group at minus one"] = listOf(invalid, signed, invalid)
        // The floor is NOT carrier-dependent, unlike the cap — stated as its own row rather than assumed.
        expected["a ROOT group at zero"] = listOf(invalid, notPositive, invalid, invalid, invalid)
        expected["a nested group at the signed 32-bit floor"] = listOf(invalid, signed, invalid)
        // Below the floor A12's reader refuses the source, so naming a class would claim a verdict it never
        // reached — the same honest report the ceiling's overflow row makes.
        expected["a nested group one below that floor"] = listOf(CK_MODEL_NOT_BUILDABLE)
        // KF221 measures an explicit `null` drawing the class with the *greater than 0* wording, so it is
        // the same defect as `0` and NOT the absent key, which simply means non-repeatable.
        expected["an explicit null count"] = listOf(invalid, notPositive, invalid)
        // …and the text form is coerced by A12 and admitted, so a reader that refused it would diverge.
        expected["the count as TEXT, which A12 coerces"] = emptyList()
        assertEquals(expected, actual, "a group's repetition count against A12's floor")
    }
}
