package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "A12 examines a declaration's message only when its DSL member is sound".
 *
 * Mirrors `rulekit.validate.laws.MessageParameterConditionDependencyLawsTest`'s refused-but-resolving rows:
 * a condition or operation that A12 refuses withholds both message verdicts whatever the class, while a class
 * about the NAME or the ERROR CODE leaves them standing beside it. Like [RuleMemberWithholdingTest] this can
 * only be seen through the assembled report, because each family's own test builds its findings directly.
 */
class MessageBehindARefusedDslTest {

    private val tab = "see" + 0x09.toChar() + "it"

    private val parameter = "see \$Premium\$"

    @Test
    fun aRefusedConditionWithholdsTheMessageWhateverItsClass() {
        val conditions = linkedMapOf(
            "a starred aggregate — the control" to "FieldFilled(Reference) And Sum(Claims*/Amount) > 0",
            "a star on the field" to "FieldFilled(Reference) And Sum(Claims/Amount*) > 0",
            "an unstarred repetition read" to "FieldFilled(Reference) And FieldFilled(Claims/Amount)",
            "a star in a scalar slot" to "FieldFilled(Reference) And FieldFilled(Claims*/Amount)",
            "the error field unreferenced" to "FieldFilled(Premium)",
        )
        val actual = linkedMapOf<String, List<String>>()
        conditions.forEach { (label, condition) ->
            actual["$label | a control character"] = ruleCodes(condition = condition, message = tab)
            actual["$label | a parameter it does not read"] = ruleCodes(condition = condition, message = parameter)
        }

        val expected = linkedMapOf(
            "a starred aggregate — the control | a control character" to listOf("INVALID_CHARACTERS"),
            "a starred aggregate — the control | a parameter it does not read" to listOf("INVALID_FIELD"),
            "a star on the field | a control character" to listOf("MVK_INVALID_WILDCARD"),
            "a star on the field | a parameter it does not read" to listOf("MVK_INVALID_WILDCARD"),
            "an unstarred repetition read | a control character" to listOf("MVK_NO_WILDCARD"),
            "an unstarred repetition read | a parameter it does not read" to listOf("MVK_NO_WILDCARD"),
            "a star in a scalar slot | a control character" to listOf("MVK_NO_WILDCARDS_ALLOWED"),
            "a star in a scalar slot | a parameter it does not read" to listOf("MVK_NO_WILDCARDS_ALLOWED"),
            "the error field unreferenced | a control character" to listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
            "the error field unreferenced | a parameter it does not read" to
                listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
        )
        assertEquals(expected, actual, "what a refused condition leaves of the message's verdict")
    }

    /** The trigger is the DSL member: a class about another member is ACCOMPANIED by the message's. */
    @Test
    fun aClassAboutTheNameOrTheCodeLeavesTheMessageStanding() {
        val actual = linkedMapOf(
            "a reserved name, a control character" to ruleCodes(name = "vk_vkProbe", message = tab),
            "a reserved name, a parameter it does not read" to ruleCodes(name = "vk_vkProbe", message = parameter),
            "a blank error code, a control character" to ruleCodes(errorCode = " ", message = tab),
            "a blank error code, a parameter it does not read" to ruleCodes(errorCode = " ", message = parameter),
        )

        val expected = linkedMapOf(
            "a reserved name, a control character" to listOf("INVALID_CHARACTERS", "MVK_RULE_NAME_INVALID"),
            "a reserved name, a parameter it does not read" to listOf("INVALID_FIELD", "MVK_RULE_NAME_INVALID"),
            "a blank error code, a control character" to listOf("INVALID_CHARACTERS", "MVK_ERROR_CODE_MISSING"),
            "a blank error code, a parameter it does not read" to
                listOf("INVALID_FIELD", "MVK_ERROR_CODE_MISSING"),
        )
        assertEquals(expected, actual, "what a class about another rule member leaves of the message")
    }

    /** The carrier twin: a computation's refused OPERATION withholds its message the same way. */
    @Test
    fun aRefusedOperationWithholdsTheComputationsMessage() {
        val actual = linkedMapOf(
            "a starred aggregate — the control | a control character" to
                computationCodes("Sum(Claims*/Amount)", tab),
            "a star on the field | a control character" to computationCodes("Sum(Claims/Amount*)", tab),
            "a star on the field | a parameter it does not read" to
                computationCodes("Sum(Claims/Amount*)", "see \$Reference\$"),
            "an unstarred repetition read | a control character" to computationCodes("[Claims/Amount] + 1", tab),
        )

        val expected = linkedMapOf(
            "a starred aggregate — the control | a control character" to listOf("INVALID_CHARACTERS"),
            "a star on the field | a control character" to listOf("MVK_INVALID_WILDCARD"),
            "a star on the field | a parameter it does not read" to listOf("MVK_INVALID_WILDCARD"),
            "an unstarred repetition read | a control character" to listOf("MVK_NO_WILDCARD"),
        )
        assertEquals(expected, actual, "what a refused operation leaves of the message's verdict")
    }

    /**
     * Two error-text classes drawn from families that judge more than the message: the Base Year terminal,
     * refused where the model declares no Base Year, and the no-text class that rides beside
     * `MVK_ERROR_MESSAGE_MISSING`. Both are withheld like the rest, while the prefixed presence class stays.
     */
    @Test
    fun theBaseYearAndNoTextClassesAreWithheldToo() {
        val refused = "FieldFilled(Reference) And FieldFilled(Claims/Amount)"
        val baseYear = "see \$BaseYear\$"
        val actual = linkedMapOf(
            "a sound condition, the Base Year — the control" to ruleCodes(message = baseYear),
            "a refused condition, the Base Year" to ruleCodes(condition = refused, message = baseYear),
            "the condition names the constant too" to
                ruleCodes(condition = "FieldFilled(Reference) And YearFromDate(BaseYear) > 2020", message = baseYear),
            "a reserved name, the Base Year" to ruleCodes(name = "vk_vkProbe", message = baseYear),
            "a sound operation, the Base Year — the control" to computationCodes("1", baseYear),
            "a refused operation, the Base Year" to computationCodes("Sum(Claims/Amount*)", baseYear),
            "a sound condition, no message entry — the control" to ruleCodes(messages = "[]"),
            "a refused condition, no message entry" to ruleCodes(condition = refused, messages = "[]"),
        )

        val expected = linkedMapOf(
            "a sound condition, the Base Year — the control" to listOf("NO_BASE_YEAR"),
            "a refused condition, the Base Year" to listOf("MVK_NO_WILDCARD"),
            "the condition names the constant too" to listOf("MVK_NO_BASE_YEAR"),
            "a reserved name, the Base Year" to listOf("MVK_RULE_NAME_INVALID", "NO_BASE_YEAR"),
            "a sound operation, the Base Year — the control" to listOf("NO_BASE_YEAR"),
            "a refused operation, the Base Year" to listOf("MVK_INVALID_WILDCARD"),
            "a sound condition, no message entry — the control" to
                listOf("MVK_ERROR_MESSAGE_MISSING", "RULE_HAS_NO_ERRORTEXT"),
            "a refused condition, no message entry" to listOf("MVK_ERROR_MESSAGE_MISSING", "MVK_NO_WILDCARD"),
        )
        assertEquals(expected, actual, "the Base Year and no-text classes behind a refused DSL")
    }

    /** Every A12 class the report names about the probe rule, sorted, so a co-firing class shows. */
    private fun ruleCodes(
        name: String = "Probe",
        errorCode: String = "C1",
        condition: String = "FieldFilled(Reference)",
        message: String = "see it",
        messages: String = """[{"locale":"en_US","text":"$message"}]""",
    ): List<String> = a12Codes(
        model(
            """{"type":"Rule","id":"r0","name":"$name","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Reference",
              "errorCode":"$errorCode","errorMessage":$messages}}""",
        ),
        "/Policy/$name",
    )

    private fun computationCodes(operation: String, message: String): List<String> = a12Codes(
        model(
            """{"type":"Computation","id":"c0","name":"PremiumCalc","Computation":{
              "computedFieldRelPath":"../Premium","computationAlternatives":[{"operation":"$operation"}],
              "errorMessage":[{"locale":"en_US","text":"$message"}]}}""",
        ),
        "/Policy/PremiumCalc",
    )

    private fun a12Codes(source: String, at: String): List<String> = check(ModelSource(source)).findings
        .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == at }.map { it.code }.sorted()

    private fun model(declaration: String): String = """
        {"header":{"id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}}
            ]}},
            $declaration
          ]}}
         ]}}}
    """.trimIndent()
}
