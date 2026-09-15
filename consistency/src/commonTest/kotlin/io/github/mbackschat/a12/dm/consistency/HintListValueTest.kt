package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the hint list's value laws.
 *
 * Mirrors `rulekit.validate.laws.HintListValueLawsTest` on the same order shape. Four rows carry the risk and
 * none is derivable from the others: an entry for a locale the model does not declare is not examined at all,
 * the `-1` sentinel is not a bound, a duplicate is counted per VALUE, and a pattern mismatch — the fourth law
 * of the same carrier — is A12's own silence rather than this family's omission.
 */
class HintListValueTest {

    private val tooLong = "MVK_VALUE_TOO_LONG"

    private val tooShort = "MVK_VALUE_TOO_SHORT"

    private val duplicate = "MVK_VALUES_DUPLICATE"

    @Test
    fun whichHintValuesDrawWhichClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a hint list with no bound — the liveness control"] = codes(hints("en_US", "AB", "cd"))
        actual["a value past the maximum"] = codes(""""maxLength":3,""" + hints("en_US", "ABCD"))
        actual["the same value at the maximum"] = codes(""""maxLength":4,""" + hints("en_US", "ABCD"))
        actual["a value below the minimum"] = codes(""""minLength":3,""" + hints("en_US", "AB"))
        actual["the same value at the minimum"] = codes(""""minLength":2,""" + hints("en_US", "AB"))
        actual["TWO values past the maximum"] =
            codes(""""maxLength":3,""" + hints("en_US", "ABCD", "EFGH"))
        actual["the same value twice"] = codes(hints("en_US", "AB", "AB"))
        actual["the same value three times"] = codes(hints("en_US", "AB", "AB", "AB"))
        actual["two distinct values each duplicated"] = codes(hints("en_US", "AB", "AB", "CD", "CD"))
        actual["a long value for an UNDECLARED locale"] =
            codes(""""maxLength":3,""" + hints("de_DE", "ABCD"))
        actual["a duplicate pair for an UNDECLARED locale"] = codes(hints("de_DE", "AB", "AB"))
        actual["the same value once in EACH locale entry"] = codes(
            """"hintList":[{"locale":"en_US","values":["AB"]},{"locale":"de_DE","values":["AB"]}]""",
        )
        actual["a long value with the maximum at the `-1` sentinel"] =
            codes(""""maxLength":-1,""" + hints("en_US", "ABCD"))
        actual["a value the type's own pattern refuses"] = codes(
            """"pattern":"[A-Z]+","errorMessage":[{"locale":"en_US","text":"letters only"}],""" +
                hints("en_US", "ab"),
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["a hint list with no bound — the liveness control"] = emptyList()
        expected["a value past the maximum"] = listOf(tooLong)
        expected["the same value at the maximum"] = emptyList()
        expected["a value below the minimum"] = listOf(tooShort)
        expected["the same value at the minimum"] = emptyList()
        expected["TWO values past the maximum"] = listOf(tooLong, tooLong)
        expected["the same value twice"] = listOf(duplicate)
        expected["the same value three times"] = listOf(duplicate)
        expected["two distinct values each duplicated"] = listOf(duplicate, duplicate)
        // A12 examines only the locales the model declares, in BOTH laws — the over-rejection guard.
        expected["a long value for an UNDECLARED locale"] = emptyList()
        expected["a duplicate pair for an UNDECLARED locale"] = emptyList()
        expected["the same value once in EACH locale entry"] = emptyList()
        expected["a long value with the maximum at the `-1` sentinel"] = emptyList()
        // A12's own silence: `CheckHintList` is readable and does not fire on a stored hint list, so
        // reproducing `MVK_VALUE_INVALID` here would refuse a model A12 takes.
        expected["a value the type's own pattern refuses"] = emptyList()
        assertEquals(expected, actual, "which hint value draws which class")
    }

    /**
     * The shapes it could not read, published so silence is never taken for agreement.
     *
     * The control matters as much as the rows: without it they would be compatible with the report always
     * carrying such a gap.
     */
    @Test
    fun theUnreadableEntriesArePublishedAsCoverageGaps() {
        val mine = { body: String ->
            check(ModelSource(order(body))).gaps
                .filter { it.reason.contains("was not judged against the type's own bounds") }
                .map { it.at to it.reason.substringAfterLast(": ") }
        }
        assertEquals(
            listOf<Pair<String?, String>>("/Order/Reference" to "a `hintList` value is not a string"),
            mine(""""hintList":[{"locale":"en_US","values":[7]}]"""),
        )
        assertEquals(
            listOf<Pair<String?, String>>(
                "/Order/Reference" to "a `hintList` entry's `values` is missing or is not an array",
            ),
            mine(""""hintList":[{"locale":"en_US"}]"""),
        )
        assertEquals(emptyList(), mine(hints("en_US", "AB")))
    }

    private fun hints(locale: String, vararg values: String): String =
        """"hintList":[{"locale":"$locale","values":[${values.joinToString(",") { "\"$it\"" }}]}]"""

    private fun codes(body: String): List<String> =
        check(ModelSource(order(body))).findings
            .filter { it.code in setOf(tooLong, tooShort, duplicate) }
            .map { it.code }

    /** The order sheet with `Reference` typed as the string type the row spells. */
    private fun order(body: String): String = """
        {"header":{"id":"order-hints","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"CustomerName","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Reference","Field":{"fieldType":{
              "type":"StringType","StringType":{$body}}}}
          ]}}
         ]}}}
    """.trimIndent()
}
