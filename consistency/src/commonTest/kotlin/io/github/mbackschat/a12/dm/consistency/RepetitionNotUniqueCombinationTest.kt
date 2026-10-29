package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the one-`RepetitionNotUnique`-per-condition gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.RepetitionNotUniqueCombinationLawsTest`. The composite-key row carries the risk:
 * it is the shape A12 offers as the FIX, so refusing it would turn the corrective into a second rejection.
 */
class RepetitionNotUniqueCombinationTest {

    private val combination = "MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE"

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"depot","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Pallets","Group":{"repeatability":9,"elements":[
                  {"type":"Field","id":"f1","name":"PalletNote","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"PalletQty","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Pallets/PalletNote",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    @Test
    fun howManyCallsItTakesToReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["one call — the control"] = codes("RepetitionNotUnique(Pallets/PalletNote)")
        actual["one call with a COMPOSITE key"] =
            codes("RepetitionNotUnique(Pallets/PalletNote, Pallets/PalletQty)")
        actual["two calls"] =
            codes("RepetitionNotUnique(Pallets/PalletNote) And RepetitionNotUnique(Pallets/PalletQty)")
        actual["two calls in opposite arms of an Or"] =
            codes("RepetitionNotUnique(Pallets/PalletNote) Or RepetitionNotUnique(Pallets/PalletQty)")

        val expected = mutableMapOf<String, List<String>>()
        expected["one call — the control"] = emptyList()
        // The shape A12 names as the corrective, so refusing it would make the fix a second rejection.
        expected["one call with a COMPOSITE key"] = emptyList()
        expected["two calls"] = listOf(combination)
        // Counted over the whole text, because A12's gate runs on the finished tree rather than per branch.
        expected["two calls in opposite arms of an Or"] = listOf(combination)
        assertEquals(expected, actual, "how many RepetitionNotUnique calls it takes to report")
    }

    /**
     * A second call inside a `Having` filter is NOT this family's, and the reader is why.
     *
     * A filter's body is handed back as authored text rather than as structure, so the call scanner does not
     * descend into it — and that under-report is the right answer here anyway: A12 refuses the construct in a
     * filter outright with `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION`, so reporting the count would name
     * a class A12 does not.
     */
    @Test
    fun aCallInsideAFilterIsNotCounted() {
        assertEquals(
            emptyList(),
            codes(
                "RepetitionNotUnique(Pallets/PalletNote) And " +
                    "Sum(Pallets*/PalletQty Having RepetitionNotUnique(Pallets/PalletNote)) > 0",
            ).filter { it == combination },
            "a RepetitionNotUnique inside a filter is another family's finding",
        )
    }

    /** The finding names the count, since the fix is to merge the calls rather than to drop one. */
    @Test
    fun theFindingNamesHowManyCallsThereAre() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"28.4.0","id":"depot","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
                    {"type":"Group","id":"g1","name":"Pallets","Group":{"repeatability":9,"elements":[
                      {"type":"Field","id":"f1","name":"PalletNote","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f2","name":"PalletQty","Field":{"fieldType":{"type":"NumberType"}}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"RepetitionNotUnique(Pallets/PalletNote) And RepetitionNotUnique(Pallets/PalletQty)",
                      "errorEntityRelPath":"../Pallets/PalletNote",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(combination, finding.code)
        assertEquals("/Depot/Probe", finding.at)
        assertEquals(true, finding.explain.contains(" 2 times"), finding.explain)
    }
}
