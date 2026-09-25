package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12 parsing a computation's members inside the one condition it generates from them
 * ([KERNEL-FINDINGS KF471]).
 *
 * Mirrors `rulekit.validate.laws.ComputationGeneratedTextLawsTest` row for row, on the subscription shape it
 * measures. Every row compares the checker's COMPLETE class list against A12's classes, so a second family
 * still reading the member as written, or the calculation module's guard class surfacing beside the token
 * class, is as visible as a wrong class. A12's codeless "operations is empty" line carries no class, so the
 * checker's list omits it.
 */
class ComputationGeneratedTextTest {

    private val guard = "FieldFilled(../Tier)"
    private val work = alternative(guard, "42.00")
    private val token = listOf("MVK_UNEXPECTED_TOKEN")

    @Test
    fun aBlankOperationIsTheIncompletenessClassOnlyWhileItIsTheOnlyAlternative() {
        val actual = linkedMapOf(
            "two guarded — the control" to codes(null, work, work),
            "one guarded, blank" to codes(null, guarded("")),
            "one unguarded, blank" to codes(null, alternative(null, "")),
            "two guarded, the second blank" to codes(null, work, guarded("")),
            "two guarded, the second whitespace" to codes(null, work, guarded("   ")),
            "two guarded, the second null" to codes(null, work, guarded(null)),
            "two guarded, the FIRST blank" to codes(null, guarded(""), work),
            "three guarded, the MIDDLE blank" to codes(null, work, guarded(""), work),
            "two, the blank one unguarded" to codes(null, work, alternative(null, "")),
            "two, the blank one unguarded and whitespace" to codes(null, work, alternative(null, "   ")),
            "two, the OTHER one unguarded" to codes(null, alternative(null, "42.00"), guarded("")),
        )
        val expected = linkedMapOf(
            "two guarded — the control" to listOf(),
            "one guarded, blank" to listOf("MVK_INCOMPLETE_INPUT"),
            "one unguarded, blank" to listOf("MVK_INCOMPLETE_INPUT"),
            "two guarded, the second blank" to token,
            "two guarded, the second whitespace" to token,
            "two guarded, the second null" to token,
            "two guarded, the FIRST blank" to token,
            "three guarded, the MIDDLE blank" to token,
            "two, the blank one unguarded" to token,
            "two, the blank one unguarded and whitespace" to token,
            "two, the OTHER one unguarded" to token,
        )
        assertEquals<Map<String, List<String>>>(expected, actual, "what a blank operation draws beside its siblings")
    }

    @Test
    fun aMemberThatStopsShortMeetsTheParenthesisAddedAfterIt() {
        val actual = linkedMapOf(
            "a lone operation ending on `+` — the control" to codes(null, guarded("42.00 +")),
            "a lone operation with `{` open — the control" to codes(null, guarded("{42.00")),
            "an operation ending on `+` beside a sibling" to codes(null, work, guarded("42.00 +")),
            "an operation with `{` open beside a sibling" to codes(null, work, guarded("{42.00")),
            "an operation with `[` open beside a sibling" to codes(null, work, guarded("[../Tier")),
            "a precondition ending on `And`" to codes(null, alternative("$guard And", "42.00")),
            "a precondition ending on `Or`" to codes(null, alternative("$guard Or", "42.00")),
            "a precondition ending on a comma" to codes(null, alternative("FieldFilled(../Tier,", "42.00")),
            "a precondition with `[` open" to codes(null, alternative("FieldFilled([../Tier", "42.00")),
            "a precondition with `(` open" to codes(null, alternative("FieldFilled(../Tier", "42.00")),
            "a second alternative's precondition ending on `And`" to
                codes(null, work, alternative("$guard And", "42.00")),
            "a common precondition ending on `And`" to codes("$guard And", alternative(null, "42.00")),
            "a common precondition with `[` open" to codes("FieldFilled([../Tier", alternative(null, "42.00")),
        )
        val expected = linkedMapOf(
            "a lone operation ending on `+` — the control" to listOf("MVK_INCOMPLETE_INPUT"),
            "a lone operation with `{` open — the control" to listOf("MVK_EXPECTED_TOKEN_NOT_FOUND"),
            "an operation ending on `+` beside a sibling" to token,
            "an operation with `{` open beside a sibling" to token,
            "an operation with `[` open beside a sibling" to token,
            "a precondition ending on `And`" to token,
            "a precondition ending on `Or`" to token,
            "a precondition ending on a comma" to token,
            "a precondition with `[` open" to token,
            "a precondition with `(` open" to listOf("MVK_EXPECTED_TOKEN_NOT_FOUND"),
            "a second alternative's precondition ending on `And`" to token,
            "a common precondition ending on `And`" to token,
            "a common precondition with `[` open" to token,
        )
        assertEquals<Map<String, List<String>>>(
            expected,
            actual,
            "what a member that stops short draws where A12 reads on after it",
        )
    }

    private fun guarded(operation: String?) = alternative(guard, operation)

    private fun alternative(precondition: String?, operation: String?): String {
        val guarded = precondition?.let { """"precondition":"$it",""" } ?: ""
        val work = if (operation == null) """"operation":null""" else """"operation":"$operation""""
        return "{$guarded$work}"
    }

    private fun codes(common: String?, vararg alternatives: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"subscription","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Subscription","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Billing","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"EffectiveFee","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":2}}}},
                  {"type":"Computation","id":"c0","name":"AlternativeProbe","Computation":{
                    "computedFieldRelPath":"../EffectiveFee",${common?.let { """"commonPrecondition":"$it",""" } ?: ""}
                    "computationAlternatives":[${alternatives.joinToString(",")}],
                    "errorCode":"P","errorMessage":[{"locale":"en_US","text":"x"}]}}
                ]}}]}}]}}}
            """.trimIndent(),
        ),
    ).findings.filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }.sorted()
}
