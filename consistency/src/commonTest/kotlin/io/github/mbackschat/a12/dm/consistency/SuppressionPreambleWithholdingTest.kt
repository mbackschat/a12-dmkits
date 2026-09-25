package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a computation's suppression is the first thing A12 reads in its condition".
 *
 * Mirrors `rulekit.validate.laws.ComputationMemberVerdictLawsTest.whereASuppressionsClassStandsAgainstTheTexts`.
 * A12 renders the member as an `@SuppressWarning(…)` preamble in front of the generated condition, so its
 * class is raised ahead of every class the operation and preconditions carry, and the message is then never
 * examined. Only the assembled report can show that: each family's own test builds its findings directly.
 */
class SuppressionPreambleWithholdingTest {

    @Test
    fun aSuppressionsClassIsRaisedAheadOfEveryClassTheTextsCarry() {
        val actual = linkedMapOf(
            "an unsupported name, sound texts — the control" to codes("FieldFilled(Base)", "[Base] + 1", BOGUS),
            "an unsupported name, an operation naming nothing" to
                codes("FieldFilled(Base)", "[NoSuchField] + 1", BOGUS),
            "an unsupported name, a precondition naming nothing" to
                codes("FieldFilled(NoSuchField)", "[Base] + 1", BOGUS),
            "an unsupported name, a rounding gate in the operation" to
                codes("FieldFilled(Base)", "RoundUpValue(Label, 2)", BOGUS),
            "an unsupported name, a read of the computed field" to
                codes("FieldFilled(Base)", "[Note] + $TEXT", BOGUS, target = "../Note"),
            "two entries, sound texts — the control" to codes("FieldFilled(Base)", "[Base] + 1", TWICE),
            "two entries, an operation naming nothing" to codes("FieldFilled(Base)", "[NoSuchField] + 1", TWICE),
            "two entries, a rounding gate in the operation" to
                codes("FieldFilled(Base)", "RoundUpValue(Label, 2)", TWICE),
            "sound texts, a control character in the message — the control" to
                codes("FieldFilled(Base)", "[Base] + 1", null, message = TAB),
            "an unsupported name, a control character in the message" to
                codes("FieldFilled(Base)", "[Base] + 1", BOGUS, message = TAB),
            "two entries, a control character in the message" to
                codes("FieldFilled(Base)", "[Base] + 1", TWICE, message = TAB),
        )

        val unsupported = listOf("MVK_INVALID_SUPPRESSED_WARNING")
        val unparsed = listOf("MVK_UNEXPECTED_TOKEN")
        val expected = linkedMapOf(
            "an unsupported name, sound texts — the control" to unsupported,
            "an unsupported name, an operation naming nothing" to unsupported,
            "an unsupported name, a precondition naming nothing" to unsupported,
            "an unsupported name, a rounding gate in the operation" to unsupported,
            "an unsupported name, a read of the computed field" to unsupported,
            "two entries, sound texts — the control" to unparsed,
            "two entries, an operation naming nothing" to unparsed,
            "two entries, a rounding gate in the operation" to unparsed,
            "sound texts, a control character in the message — the control" to listOf("INVALID_CHARACTERS"),
            "an unsupported name, a control character in the message" to unsupported,
            "two entries, a control character in the message" to unparsed,
        )
        assertEquals(expected, actual, "where a suppression's class stands against the texts'")
    }

    private fun codes(
        precondition: String,
        operation: String,
        suppressed: String?,
        target: String = "../Weight",
        message: String = "see it",
    ): List<String> = check(ModelSource(model(precondition, operation, suppressed, target, message))).findings
        .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Depot/Probe" }
        .map { it.code }.sorted()

    /** The courier depot of the laws test. */
    private fun model(
        precondition: String,
        operation: String,
        suppressed: String?,
        target: String,
        message: String,
    ): String = """
        {"header":{"id":"courier-depot","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Weight","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f1","name":"Base","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f2","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f4","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Computation","id":"c0","name":"Probe","Computation":{
              "computedFieldRelPath":"$target",${suppressed?.let { "\"errorCodesToSuppress\":$it," } ?: ""}
              "computationAlternatives":[{"precondition":"$precondition","operation":"$operation"}],
              "errorMessage":[{"locale":"en_US","text":"$message"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        const val BOGUS = """["MVK_TOTALLY_BOGUS"]"""
        const val TWICE = """["MVK_INVALID_COMPARE_DEC_PLACES","MVK_INVALID_COMPARE_DEC_PLACES"]"""

        /** A text constant as the model text carries it: the operation is interpolated into JSON as written. */
        const val TEXT = "\\\"x\\\""

        /** A TAB in the message, escaped as the JSON the fixture is: a raw control character is unreadable. */
        const val TAB = "see\\tit"
    }
}
