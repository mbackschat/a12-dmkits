package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a class drawn during the parse withholds the calculation module's classes".
 *
 * Mirrors `rulekit.validate.laws.ComputationMemberVerdictLawsTest
 * .whetherAParseTimeClassWithholdsTheCalculationModulesClasses`. The withholding is applied where the report is
 * assembled, so only the assembled report can show it: each family's own test builds its findings directly.
 */
class CalculationModuleWithholdingTest {

    @Test
    fun aParseTimeClassWithholdsTheCalculationModulesClasses() {
        val actual = linkedMapOf(
            "a read of the computed field — the control" to codes("FieldFilled(Base)", "[Note] + $TEXT", "../Note"),
            "the same read, a rounding gate in the operation" to
                codes("FieldFilled(Base)", "[Note] + RoundUpValue(Label, 2)", "../Note"),
            "the same read, an arity gate in the precondition" to
                codes("FieldFilled(Base, Label)", "[Note] + $TEXT", "../Note"),
            "the same read, a name in the operation that resolves to nothing" to
                codes("FieldFilled(Base)", "[Note] + [NoSuchField]", "../Note"),
            "a text into an enumeration — the control" to codes("FieldFilled(Base)", "[Label]", "../Status"),
            "the same text, an arity gate in the precondition" to
                codes("FieldFilled(Base, Label)", "[Label]", "../Status"),
            "the same text, a name in the precondition that resolves to nothing" to
                codes("FieldFilled(NoSuchField)", "[Label]", "../Status"),
        )

        val expected = linkedMapOf(
            "a read of the computed field — the control" to listOf("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"),
            "the same read, a rounding gate in the operation" to listOf("MVK_NO_NUMBER"),
            "the same read, an arity gate in the precondition" to listOf("MVK_PARAMSIZE_INVALID1"),
            "the same read, a name in the operation that resolves to nothing" to listOf("MVK_INVALID_ENTITY"),
            "a text into an enumeration — the control" to listOf("MVK_ERROR_INVALID_ENUM_CALCULATION"),
            "the same text, an arity gate in the precondition" to listOf("MVK_PARAMSIZE_INVALID1"),
            "the same text, a name in the precondition that resolves to nothing" to listOf("MVK_INVALID_ENTITY"),
        )
        assertEquals(expected, actual, "whether a parse-time class withholds the calculation module's classes")
    }

    private fun codes(precondition: String, operation: String, target: String): List<String> =
        check(ModelSource(model(precondition, operation, target))).findings
            .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Depot/Probe" }
            .map { it.code }.sorted()

    /** A courier depot mirroring the laws test's carrier. */
    private fun model(precondition: String, operation: String, target: String): String = """
        {"header":{"id":"courier-depot","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Weight","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f1","name":"Base","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f2","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f4","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f3","name":"Status","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"OPEN"},{"value":"CLOSED"}]}}}},
            {"type":"Computation","id":"c0","name":"Probe","Computation":{
              "computedFieldRelPath":"$target",
              "computationAlternatives":[{"precondition":"$precondition","operation":"$operation"}],
              "errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        /**
         * A text constant as the model text carries it: the operation is interpolated into JSON as written. A
         * self-read into a NUMBER target is left out on purpose — the family is silent there
         * (`ComputationSelfRead`'s scale-gate note), so every row over one would pass whatever the withholding did.
         */
        const val TEXT = "\\\"x\\\""
    }
}
