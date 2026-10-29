package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of KF569's six declaration readings, one discriminating row and one control each, so the
 * JS checker is held to them as well as the JVM one. A12's answer to every row was measured by
 * `adapter.laws.CheckerDeclarationAdmissionAgreementTest`; this class only replays it.
 */
class DeclarationAdmissionReadingsTest {

    /** A policy model whose one field declares [fieldType], or whose one nested group is named [group]. */
    private fun codes(fieldType: String = STRING, filter: String? = null, group: String? = null): List<String> {
        val variant = filter?.let { ""","variantFilter":$it""" }.orEmpty()
        val nested = group?.let {
            """,{"type":"Group","id":"/Policy/$it","name":"$it","Group":{"elements":[
                {"type":"Field","id":"/Policy/$it/Note","name":"Note","Field":{"fieldType":$STRING}}]}}"""
        }.orEmpty()
        return checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"28.4.0","id":"policy","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"/Policy/Item","name":"Item","Field":{"fieldType":$fieldType$variant}}$nested
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.justifiesRejection }.map { it.code }
    }

    private fun enumeration(values: String, sValue: String? = null) =
        """{"type":"EnumerationType","EnumerationType":{"values":[$values]""" +
            sValue?.let { ""","sValue":"$it"""" }.orEmpty() + "}}"

    private fun number(members: String) = """{"type":"NumberType","NumberType":{$members}}"""
    private fun string(members: String) = """{"type":"StringType","StringType":{$members}}"""

    @Test
    fun eachReadingAgainstItsControl() {
        val repeatedToken = enumeration("""{"value":"BASIC"},{"value":"BASIC"}""")
        val actual = linkedMapOf(
            "a repeated token on a field ONLY a variant keeps" to codes(repeatedToken, ONLY),
            "a repeated token" to codes(repeatedToken),
            "a label for an undeclared locale only" to codes(enumeration(
                """{"value":"BASIC","label":[{"locale":"fr_FR","text":"Base"}]},{"value":"PLUS"}""")),
            "declared beside undeclared, against undeclared alone" to codes(enumeration(
                """{"value":"BASIC","label":[{"locale":"en_US","text":"Basic"},{"locale":"fr_FR","text":"Base"}]},""" +
                    """{"value":"PLUS","label":[{"locale":"fr_FR","text":"Plus"}]}""")),
            "an sValue padded with a space" to codes(enumeration("""{"value":"BASIC"},{"value":"PLUS"}""", " BASIC")),
            "an sValue that is a tab" to codes(enumeration("""{"value":"BASIC"},{"value":"PLUS"}""", "\\t")),
            "an sValue that is a no-break space" to
                codes(enumeration("""{"value":"BASIC"},{"value":"PLUS"}""", "\u00A0")),
            "a group named __attachment_metadata" to codes(group = "__attachment_metadata"),
            "a group named __meta_x" to codes(group = "__meta_x"),
            "1.4 over 1, no fraction" to codes(number(""""maxFractionalDigits":0,"minValue":1.4,"maxValue":1""")),
            "1.04 over 1, one fraction digit" to
                codes(number(""""maxFractionalDigits":1,"minValue":1.04,"maxValue":1""")),
            "line breaks 2" to codes(string(""""noValueValidation":true,"lineBreaksPermitted":2""")),
            "line breaks \"\"" to codes(string(""""noValueValidation":true,"lineBreaksPermitted":"""" + "\"")),
            "line breaks 1.5" to codes(string(""""noValueValidation":true,"lineBreaksPermitted":1.5""")),
            "positivesOnly 1 beside a 16-character length" to
                codes(number(""""maxFractionalDigits":0,"maxLength":16,"positivesOnly":1""")),
            "an unbindable positivesOnly beside a 17-character length" to
                codes(number(""""maxFractionalDigits":0,"maxLength":17,"positivesOnly":1.5""")),
        )
        val expected = linkedMapOf(
            // A12's basic sentence survives the filter that hides the class ([KERNEL-FINDINGS KF626]).
            "a repeated token on a field ONLY a variant keeps" to listOf("RK_ENUM_VALUE_DUPLICATE"),
            "a repeated token" to listOf("MVK_INTERNAL_VALUES_DUPLICATE", "RK_ENUM_VALUE_DUPLICATE"),
            "a label for an undeclared locale only" to emptyList(),
            "declared beside undeclared, against undeclared alone" to listOf("MVK_COUNT_OF_VALUES_INVALID"),
            "an sValue padded with a space" to emptyList(),
            "an sValue that is a tab" to emptyList(),
            "an sValue that is a no-break space" to listOf("MVK_S_VALUE_INVALID"),
            "a group named __attachment_metadata" to emptyList(),
            "a group named __meta_x" to listOf("NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN"),
            "1.4 over 1, no fraction" to emptyList(),
            "1.04 over 1, one fraction digit" to listOf("MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE"),
            "line breaks 2" to emptyList(),
            "line breaks \"\"" to listOf("MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK"),
            "line breaks 1.5" to listOf("CK_MODEL_NOT_BUILDABLE"),
            "positivesOnly 1 beside a 16-character length" to listOf("MVK_MAX_LENGTH_TOO_BIG"),
            // A12 does not read the model (KF569), and with the sign unknown the budget is too: no length class.
            "an unbindable positivesOnly beside a 17-character length" to listOf("CK_MODEL_NOT_BUILDABLE"),
        )
        assertEquals<Map<String, List<String>>>(expected, actual)
    }

    /**
     * KF582's magnitude limit, replayed: judged on every field, a deleted one included, and beside the printed-bound
     * checks; a bound at a limit `Math.pow` need not compute exactly is disclosed rather than judged.
     */
    @Test
    fun aBoundPastItsDigitLimitIsReportedOnEveryField() {
        val actual = linkedMapOf(
            "max 999999999999999.4 at scale 0" to codes(number(""""maxFractionalDigits":0,"maxValue":999999999999999.4""")),
            "min 1e15 at scale 0" to codes(number(""""maxFractionalDigits":0,"minValue":1e15""")),
            "max -1e15 at scale 0" to codes(number(""""maxFractionalDigits":0,"maxValue":-1e15""")),
            "max 1e15 on a deleted field" to codes(number(""""maxFractionalDigits":0,"maxValue":1e15"""), ONLY),
            "max 1e14 over min 1 at scale 1" to codes(number(""""maxFractionalDigits":1,"minValue":1e14,"maxValue":1""")),
        )
        val expected = linkedMapOf(
            "max 999999999999999.4 at scale 0" to listOf(MAX_EXCEEDED),
            "min 1e15 at scale 0" to listOf("RK_BOUND_TOO_MANY_DIGITS", MIN_EXCEEDED),
            "max -1e15 at scale 0" to listOf("RK_BOUND_TOO_MANY_DIGITS"),
            "max 1e15 on a deleted field" to listOf(MAX_EXCEEDED),
            "max 1e14 over min 1 at scale 1" to listOf("MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE", MIN_EXCEEDED),
        )
        assertEquals<Map<String, List<String>>>(expected, actual.mapValues { it.value.sorted() })
        val atTheLimit = checkRead(ModelSource(
            """{"header":{"modelVersion":"28.4.0","id":"policy","locales":[{"code":"en_US"}]},"content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[{"type":"Field","id":"/Policy/Item",
              "name":"Item","Field":{"fieldType":${number(""""maxFractionalDigits":-15,"maxValue":${1e30 - 1e15}""")}}}]}}]}}}""",
        ))
        assertEquals(emptyList(), atTheLimit.findings.filter { it.code == MAX_EXCEEDED })
        assertEquals(1, atTheLimit.gaps.count { it.at == "/Policy/Item" && "ulps" in it.reason }, "${atTheLimit.gaps}")
    }

    private companion object {
        const val STRING = """{"type":"StringType"}"""
        const val ONLY = """{"filterType":"ONLY","variants":["Broker"]}"""
        const val MIN_EXCEEDED = "RK_NUMBER_MIN_VALUE_EXCEEDED"
        const val MAX_EXCEEDED = "RK_NUMBER_MAX_VALUE_EXCEEDED"
    }
}
