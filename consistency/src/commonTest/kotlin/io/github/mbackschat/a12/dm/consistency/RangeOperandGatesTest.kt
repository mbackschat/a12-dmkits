package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the two substring operators' operand chain.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.RangeOperandGateLawsTest`, the doubly-defective cells included: the interval and the
 * field are independent mistakes, so which of the two answers is the part a reproduction gets wrong.
 */
class RangeOperandGatesTest {

    @Test
    fun whichDeclarationsTheSubstringOperatorsCut() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a string field — the control"] = cutAsText("Sku")
        actual["a custom field"] = cutAsText("Supplier")
        actual["an extensible-enum field, which is a string carrying suggestions"] = cutAsText("Region")
        actual["an enumeration field"] = cutAsText("Tier")
        actual["a number field"] = cutAsText("UnitCount")
        actual["a boolean field"] = cutAsText("Perishable")
        actual["a confirm field"] = cutAsText("Acknowledged")
        actual["a date field"] = cutAsText("ShippedOn")
        actual["a date-range field"] = cutAsText("Window")
        actual["a string field waiving value validation"] = cutAsText("RawNote")
        actual["the string control read as a NUMBER"] = cutAsNumber("Sku")
        actual["the enumeration read as a NUMBER"] = cutAsNumber("Tier")
        actual["a number field read as a NUMBER"] = cutAsNumber("UnitCount")

        val expected = mutableMapOf<String, List<String>>()
        expected["a string field — the control"] = emptyList()
        expected["a custom field"] = emptyList()
        expected["an extensible-enum field, which is a string carrying suggestions"] = emptyList()
        expected["an enumeration field"] = listOf("MVK_INVALID_TYPE")
        expected["a number field"] = listOf("MVK_INVALID_TYPE")
        expected["a boolean field"] = listOf("MVK_INVALID_TYPE")
        expected["a confirm field"] = listOf("MVK_INVALID_TYPE")
        expected["a date field"] = listOf("MVK_INVALID_TYPE")
        expected["a date-range field"] = listOf("MVK_INVALID_TYPE")
        expected["a string field waiving value validation"] = listOf("MVK_INVALID_RAW_TYPE")
        expected["the string control read as a NUMBER"] = emptyList()
        expected["the enumeration read as a NUMBER"] = listOf("MVK_INVALID_TYPE")
        expected["a number field read as a NUMBER"] = listOf("MVK_INVALID_TYPE")
        assertEquals(expected, actual, "which declarations the substring operators cut")
    }

    @Test
    fun theOrderAmongTheOperandGates() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an invalid interval alone — the control"] = codes("""RangeAsString(Sku, 0, 3) == \"ABC\"""")
        actual["a GROUP operand, interval also invalid"] =
            codes("""RangeAsString(Warehouse, 0, 3) == \"ABC\"""")
        actual["a STARRED operand, interval also invalid"] =
            codes("""RangeAsString(Lines*/LineCode, 0, 3) == \"ABC\"""")
        actual["a CATEGORY access, interval also invalid"] =
            codes("""RangeAsString(Tier -> Band, 0, 3) == \"ABC\"""")
        actual["an invalid interval on a wrong-TYPE field"] =
            codes("""RangeAsString(UnitCount, 0, 3) == \"ABC\"""")
        actual["an invalid interval on a field waiving value validation"] =
            codes("""RangeAsString(RawNote, 0, 3) == \"ABC\"""")
        actual["a CATEGORY access with a valid interval"] = cutAsText("Tier -> Band")
        actual["a STARRED GROUP operand, interval also invalid"] =
            codes("""RangeAsString(Lines*, 0, 3) == \"ABC\"""")
        actual["an operand that ITERATES, nothing else wrong"] = cutAsText("Lines/LineCode")
        actual["an operand that ITERATES, interval also invalid"] =
            codes("""RangeAsString(Lines/LineCode, 0, 3) == \"ABC\"""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an invalid interval alone — the control"] = listOf("MVK_INVALID_INTERVAL")
        expected["a GROUP operand, interval also invalid"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["a STARRED operand, interval also invalid"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a CATEGORY access, interval also invalid"] = listOf("MVK_CATEGORY_NOT_ALLOWED")
        expected["an invalid interval on a wrong-TYPE field"] = listOf("MVK_INVALID_INTERVAL")
        expected["an invalid interval on a field waiving value validation"] = listOf("MVK_INVALID_INTERVAL")
        expected["a CATEGORY access with a valid interval"] = listOf("MVK_CATEGORY_NOT_ALLOWED")
        expected["a STARRED GROUP operand, interval also invalid"] = listOf("MVK_NO_GROUPS_ALLOWED")
        // A12 answers MVK_NO_WILDCARD from the gate outside this method, which pre-empts all six classes —
        // so the silence is what keeps the checker from naming its own chain's class on both rows.
        expected["an operand that ITERATES, nothing else wrong"] = emptyList()
        expected["an operand that ITERATES, interval also invalid"] = emptyList()
        assertEquals(expected, actual, "the order among the operand gates")
    }

    private fun cutAsText(operand: String): List<String> = codes("""RangeAsString($operand, 1, 3) == \"ABC\"""")

    private fun cutAsNumber(operand: String): List<String> = codes("RangeAsNumber($operand, 1, 3) > 0")

    /** Only this family's codes, so another family's finding cannot make a row look right. */
    private fun codes(probe: String): List<String> =
        checkRead(ModelSource(model(probe))).findings
            .filter { it.code in OWNED }
            .map { it.code }

    /** An e-commerce catalogue mirroring the laws test's carrier. */
    private fun model(probe: String): String = """
        {"header":{"modelVersion":"28.4.0","id":"catalog-range","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"RawNote","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true}}}},
            {"type":"Field","id":"f3","name":"Region","Field":{"fieldType":{"type":"StringType",
              "StringType":{"hintList":[{"locale":"en_US","values":["EU","US"]}]}}}},
            {"type":"Field","id":"f4","name":"Supplier","Field":{"fieldType":{"type":"CustomFieldType",
              "CustomFieldType":{"name":"SupplierCode"}}}},
            {"type":"Field","id":"f5","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"BRONZE"},{"value":"GOLD"}],
              "categories":[{"name":"Band","values":[{"value":"LOW"},{"value":"HIGH"}]}]}}}},
            {"type":"Field","id":"f6","name":"UnitCount","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f7","name":"Perishable","Field":{"fieldType":{"type":"BooleanType"}}},
            {"type":"Field","id":"f8","name":"Acknowledged","Field":{"fieldType":{"type":"ConfirmType"}}},
            {"type":"Field","id":"f9","name":"ShippedOn","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"dd.MM.yyyy"}}}},
            {"type":"Field","id":"f10","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
              "DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
            {"type":"Group","id":"g1","name":"Warehouse","Group":{"elements":[
              {"type":"Field","id":"f11","name":"WarehouseCode","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Lines","Group":{"repeatability":10,"elements":[
              {"type":"Field","id":"f12","name":"LineCode","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Reference) And $probe",
              "errorEntityRelPath":"../Reference",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf(
            "MVK_NO_GROUPS_ALLOWED",
            "MVK_NO_WILDCARDS_ALLOWED",
            "MVK_CATEGORY_NOT_ALLOWED",
            "MVK_INVALID_INTERVAL",
            "MVK_INVALID_TYPE",
            "MVK_INVALID_RAW_TYPE",
        )
    }
}
