package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which text counts as BLANK in a member A12 requires. The answer is two tests rather than one, and neither is
 * Kotlin's `isBlank`.
 *
 * A12 asks a member-level presence question of a rule's `errorCode` and `errorCondition` and of a custom type's
 * `name`, and that question counts exactly what Java's `Character.isWhitespace` counts: a vertical tab, an em
 * space and an ideographic space are blank there, and the three no-break spaces are not. A condition or an
 * operation then reaches the LEXER, whose own whitespace is five characters, so a condition of vertical tabs is
 * both missing and unlexable. Every row is the Kernel's, from the `whichSpacesMake…` tests of
 * `RuleCodeAndSeverityLawsTest`, `RuleConditionAndTargetSlotLawsTest`, `ComputationAlternativeSlotLawsTest` and
 * `FieldTypeBodyMemberLawsTest` (kernel 30.8.1, 2026-09-24).
 *
 * A no-break space in an error code or a type name draws a verdict A12 states in prose alone, so the checker is
 * silent on those rows: it can never cite a class A12 did not name.
 */
class MemberBlanknessTest {

    private val nonBreaking = listOf('\u00a0', '\u2007', '\u202f')
    private val javaWhitespace = listOf('\u000b', '\u2003', '\u3000')

    private fun json(text: String) = JsonPrimitive(text).toString()

    private fun rule(code: String = "P", condition: String = "FieldFilled(Price)") = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
              "errorCondition":${json(condition)},"errorEntityRelPath":"../Price",
              "errorCode":${json(code)},"errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun computation(operation: String) = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Computation","id":"c0","name":"TotalCalc","Computation":{
              "computedFieldRelPath":"../Total","computationAlternatives":[{"operation":${json(operation)}}],
              "errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun customType(name: String) = ModelSource(
        """
        {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Tier","Field":{
              "fieldType":{"type":"CustomFieldType","CustomFieldType":{"name":${json(name)}}}}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /** The A12 classes the whole report rejects with, sorted, which is what the Kernel rows state. */
    private fun rejections(source: ModelSource) =
        checkRead(source).findings.filter { it.justifiesRejection }.map { it.code }.distinct().sorted()

    private fun Char.label() = "U+" + code.toString(16).uppercase().padStart(4, '0')

    @Test
    fun anErrorCodeIsBlankWhereJavaSeesOnlyWhitespace() {
        val actual = linkedMapOf("P — the control" to rejections(rule(code = "P")), "   " to rejections(rule(code = "   ")))
        (nonBreaking + javaWhitespace).forEach { actual[it.label()] = rejections(rule(code = "$it")) }

        val expected = linkedMapOf("P — the control" to emptyList(), "   " to listOf("MVK_ERROR_CODE_MISSING"))
        // A non-breaking space is not blank, so it reaches A12's code alphabet, which refuses it (KF578).
        nonBreaking.forEach { expected[it.label()] = listOf("RK_ERROR_CODE_ALPHABET") }
        javaWhitespace.forEach { expected[it.label()] = listOf("MVK_ERROR_CODE_MISSING") }
        assertEquals(expected, actual, "which spaces make a rule's errorCode blank")
    }

    @Test
    fun aConditionIsMissingWhereJavaSeesOnlyWhitespaceAndIncompleteWhereTheLexerDoes() {
        val actual = linkedMapOf(
            "FieldFilled(Price) — the control" to rejections(rule()),
            "   " to rejections(rule(condition = "   ")),
        )
        (nonBreaking + javaWhitespace).forEach { actual[it.label()] = rejections(rule(condition = "$it")) }

        val expected = linkedMapOf(
            "FieldFilled(Price) — the control" to emptyList(),
            "   " to listOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT"),
        )
        nonBreaking.forEach { expected[it.label()] = listOf("MVK_LEXER_STANDARD_ERROR") }
        javaWhitespace.forEach { expected[it.label()] = listOf("MVK_CONDITION_MISSING", "MVK_LEXER_STANDARD_ERROR") }
        assertEquals(expected, actual, "which spaces make a rule's errorCondition blank")
    }

    @Test
    fun anOperationHasNoPresenceTestBeforeTheLexer() {
        val actual = linkedMapOf("1 — the control" to rejections(computation("1")), "   " to rejections(computation("   ")))
        (nonBreaking + javaWhitespace).forEach { actual[it.label()] = rejections(computation("$it")) }

        val expected = linkedMapOf("1 — the control" to emptyList(), "   " to listOf("MVK_INCOMPLETE_INPUT"))
        (nonBreaking + javaWhitespace).forEach { expected[it.label()] = listOf("MVK_LEXER_STANDARD_ERROR") }
        assertEquals(expected, actual, "which spaces make a computation's operation blank")
    }

    @Test
    fun aCustomTypeNameIsBlankWhereJavaSeesOnlyWhitespace() {
        val actual = linkedMapOf("Sig — the control" to rejections(customType("Sig")), "   " to rejections(customType("   ")))
        (nonBreaking + javaWhitespace).forEach { actual[it.label()] = rejections(customType("$it")) }

        val expected = linkedMapOf("Sig — the control" to emptyList(), "   " to listOf("CUSTOM_FIELD_TYPE_CODENAME_MISSING"))
        nonBreaking.forEach { expected[it.label()] = emptyList() }
        javaWhitespace.forEach { expected[it.label()] = listOf("CUSTOM_FIELD_TYPE_CODENAME_MISSING") }
        assertEquals(expected, actual, "which spaces make a custom type's name blank")
    }
}
