package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the two conditions that share the `Valid` keyword.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ValidityConditionOperandLawsTest`. The rows that carry the file are the custom-type
 * one — the kind gate admits less than its code's name promises — and the extract-operator slot, which reads
 * a field and is still "only constants" to A12.
 */
class ValidityConditionOperandsTest {

    private val wrongKind = listOf("MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM")
    private val emptyName = listOf("MVK_NO_EMPTY")
    private val onlyConstants = listOf("MVK_ONLY_CONSTANTS_USED")

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"order-validity-conditions","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Blob","Field":{"fieldType":{"type":"StringType",
                  "StringType":{"lineBreaksPermitted":true,"noValueValidation":true}}}},
                {"type":"Field","id":"f3","name":"Channel","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}]}}}},
                {"type":"Field","id":"f4","name":"Premium","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f5","name":"Day","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"minValue":"0","maxLength":2}}}},
                {"type":"Field","id":"f6","name":"Year","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"minValue":"0","maxLength":4}}}},
                {"type":"Field","id":"f7","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f8","name":"Acknowledged","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Field","id":"f9","name":"Courier","Field":{"fieldType":{"type":"CustomFieldType",
                  "CustomFieldType":{"name":"CourierCode"}}}},
                {"type":"Field","id":"f10","name":"OrderedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Group","id":"g1","name":"Broker","Group":{"elements":[
                  {"type":"Field","id":"f11","name":"BrokerName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Items","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f12","name":"ItemCode","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    /** The probe also READS the rule's error target, so the unreferenced-target family stays out of the row. */
    private fun valid(probe: String): List<String> = codes("FieldFilled(Reference) And $probe")
        .filter { it != "MVK_ERROR_FIELD_NOT_REFERENCED" }

    /** Only the grammar's two shapes parse: a `Date` construction alone, or a field and a type name. */
    @Test
    fun whatTheGrammarAdmitsAsAValidityCondition() {
        val token = listOf("MVK_UNEXPECTED_TOKEN")
        assertEquals(
            mapOf(
                "a field alone" to token,
                "a date field alone" to token,
                "the negated spelling of a field alone" to token,
                "a field, a type name and a third operand" to token,
                "a date shift alone" to token,
                "a date extract alone" to token,
            ),
            mapOf(
                "a field alone" to valid("Valid(Code)"),
                "a date field alone" to valid("Valid(OrderedOn)"),
                "the negated spelling of a field alone" to valid("Invalid(Code)"),
                "a field, a type name and a third operand" to valid("Valid(Code, \\\"EMail\\\", \\\"IBAN\\\")"),
                "a date shift alone" to valid("Valid(AddDays(OrderedOn, 1))"),
                "a date extract alone" to valid("Valid(DayFromDate(OrderedOn))"),
            ),
        )
    }

    @Test
    fun whatThePredefinedTypeArmDemandsOfItsField() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a string field — the control"] = valid("Valid(Code, \\\"EMail\\\")")
        actual["an enumeration field"] = valid("Valid(Channel, \\\"EMail\\\")")
        actual["a group"] = valid("Valid(Broker, \\\"EMail\\\")")
        actual["a starred field"] = valid("Valid(Items*/ItemCode, \\\"EMail\\\")")
        actual["a string waiving value validation"] = valid("Valid(Blob, \\\"EMail\\\")")
        actual["a number field"] = valid("Valid(Premium, \\\"EMail\\\")")
        actual["a boolean field"] = valid("Valid(Expedited, \\\"EMail\\\")")
        actual["a confirm field"] = valid("Valid(Acknowledged, \\\"EMail\\\")")
        actual["a date field"] = valid("Valid(OrderedOn, \\\"EMail\\\")")
        actual["a CUSTOM-typed field"] = valid("Valid(Courier, \\\"EMail\\\")")
        actual["an empty type name"] = valid("Valid(Code, \\\"\\\")")
        actual["a type name nothing registers"] = valid("Valid(Code, \\\"NotRegistered\\\")")
        actual["a wrong KIND and an empty name together"] = valid("Valid(Premium, \\\"\\\")")
        actual["a raw string and an empty name together"] = valid("Valid(Blob, \\\"\\\")")
        actual["the negated spelling, same gates"] = valid("Invalid(Premium, \\\"EMail\\\")")

        val expected = mutableMapOf<String, List<String>>()
        expected["a string field — the control"] = emptyList()
        expected["an enumeration field"] = emptyList()
        expected["a group"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["a starred field"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a string waiving value validation"] = listOf("MVK_INVALID_RAW_TYPE")
        expected["a number field"] = wrongKind
        expected["a boolean field"] = wrongKind
        expected["a confirm field"] = wrongKind
        expected["a date field"] = wrongKind
        // The extensible-enum disjunct is a FLAG on a string declaration rather than a kind, so a custom
        // type is outside the admitted set — the row a reader taking the set from the code's name gets wrong.
        expected["a CUSTOM-typed field"] = wrongKind
        expected["an empty type name"] = emptyName
        // Opaque at model-check time: predefined types are a runtime SPI, so only the EMPTY name is refused.
        expected["a type name nothing registers"] = emptyList()
        expected["a wrong KIND and an empty name together"] = wrongKind
        expected["a raw string and an empty name together"] = listOf("MVK_INVALID_RAW_TYPE")
        expected["the negated spelling, same gates"] = wrongKind
        assertEquals(expected, actual, "what the predefined-type arm demands of its field")
    }

    @Test
    fun whatTheDateArmDemandsOfItsConstruction() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a construction naming a field — the control"] =
            valid("Valid(Date(Day, \\\"02\\\", \\\"2024\\\"))")
        actual["a construction of constants only"] = valid("Valid(Date(\\\"01\\\", \\\"02\\\", \\\"2024\\\"))")
        actual["the field in the LAST slot"] = valid("Valid(Date(\\\"01\\\", \\\"02\\\", Year))")
        actual["the field in the MIDDLE slot"] = valid("Valid(Date(\\\"01\\\", Day, \\\"2024\\\"))")
        actual["an EXTRACT operator in a slot, which names a field inside itself"] =
            valid("Valid(Date(DayFromDate(OrderedOn), \\\"02\\\", \\\"2024\\\"))")
        actual["an unresolvable construction of constants"] =
            valid("Valid(Date(\\\"32\\\", \\\"02\\\", \\\"2024\\\"))")
        actual["the negated spelling, same question"] =
            valid("Invalid(Date(\\\"01\\\", \\\"02\\\", \\\"2024\\\"))")

        val expected = mutableMapOf<String, List<String>>()
        expected["a construction naming a field — the control"] = emptyList()
        expected["a construction of constants only"] = onlyConstants
        expected["the field in the LAST slot"] = emptyList()
        expected["the field in the MIDDLE slot"] = emptyList()
        // A12's test is `entitySpec() == null` PER SLOT, and an extract operator is a different grammar
        // alternative — so a construction that plainly reads a field is still constants-only to it.
        expected["an EXTRACT operator in a slot, which names a field inside itself"] = onlyConstants
        // The slot gate answers first and `DATE_CONSTRUCTION_SLOT` owns it, which is why this family reads
        // the construction through that family's own walk rather than re-examining the slots here.
        expected["an unresolvable construction of constants"] = listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        expected["the negated spelling, same question"] = onlyConstants
        assertEquals(expected, actual, "what the date arm demands of its construction")
    }

    @Test
    fun aDoubledQuoteIsANonemptyPredefinedTypeName() {
        assertEquals(emptyName, valid("Valid(Code, \\\"\\\")"), "empty name control")
        assertEquals(emptyList(), valid("Valid(Code, \\\"\\\"\\\"\\\")"), "one decoded quote")
    }
}
