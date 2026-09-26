package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of when two declarations may be compared once their LABELS are taken into account.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EnumDisplayComparabilityLawsTest`. The rows that must come back EMPTY carry the
 * risk here: each is a shape A12 admits, and a finding on any of them would refuse a legal model.
 */
class EnumDisplayComparabilityTest {

    @Test
    fun whenTwoEnumerationsMayBeCompared() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two textless enumerations — the control"] = compare("[Plain] == [PlainTwin]")
        actual["identity labels against a textless enumeration"] = compare("[Mirrored] == [Plain]")
        actual["two labelled enumerations agreeing on every value"] = compare("[Labelled] == [LabelledTwin]")
        actual["a labelled enumeration against a textless one"] = compare("[Labelled] == [Plain]")
        actual["a shared STORED token carrying two texts"] = compare("[Labelled] == [TokenConflict]")
        actual["a shared DISPLAY text standing for two tokens"] = compare("[Labelled] == [DisplayConflict]")
        actual["a labelled enumeration against one labelled only for an UNDECLARED locale"] =
            compare("[Labelled] == [ForeignLabelled]")
        actual["that undeclared-locale enumeration against a TEXTLESS one"] =
            compare("[ForeignLabelled] == [Plain]")
        actual["a conflicting pair read through a CATEGORY"] = compare("[Labelled] == [Categorized -> Band]")
        actual["the same pair read without the category"] = compare("[Labelled] == [Categorized]")

        val expected = mutableMapOf<String, List<String>>()
        expected["two textless enumerations — the control"] = emptyList()
        expected["identity labels against a textless enumeration"] = emptyList()
        expected["two labelled enumerations agreeing on every value"] = emptyList()
        expected["a labelled enumeration against a textless one"] = listOf(MIXED)
        expected["a shared STORED token carrying two texts"] = listOf(CLASHING)
        expected["a shared DISPLAY text standing for two tokens"] = listOf(CLASHING)
        expected["a labelled enumeration against one labelled only for an UNDECLARED locale"] = listOf(MIXED)
        expected["that undeclared-locale enumeration against a TEXTLESS one"] = emptyList()
        // The category access returns before the gate on A12's side and is unreadable as a bare reference on
        // this one, so the identical conflict is silent through the projection and named without it.
        expected["a conflicting pair read through a CATEGORY"] = emptyList()
        expected["the same pair read without the category"] = listOf(CLASHING)
        assertEquals(expected, actual, "when two enumerations may be compared")
    }

    @Test
    fun whatTheTextGateReadsOnTheTextSide() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a textless enumeration against a string field — the control"] = compare("[Plain] == [Holder]")
        actual["identity labels against a string field"] = compare("[Mirrored] == [Holder]")
        actual["a labelled enumeration against a string field"] = compare("[Labelled] == [Holder]")
        actual["the same with the string side written first"] = compare("[Holder] == [Labelled]")
        actual["the same pair compared for INEQUALITY"] = compare("[Labelled] != [Holder]")
        actual["a labelled enumeration against a string-valued FUNCTION of that field"] =
            compare("[Labelled] == RangeAsString(Holder, 1, 3)")
        actual["a labelled enumeration read through a CATEGORY against a string field"] =
            compare("[Categorized -> Band] == [Holder]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a textless enumeration against a string field — the control"] = emptyList()
        expected["identity labels against a string field"] = emptyList()
        expected["a labelled enumeration against a string field"] = listOf(MIXED)
        expected["the same with the string side written first"] = listOf(MIXED)
        expected["the same pair compared for INEQUALITY"] = listOf(MIXED)
        // A12's gate is `isFeldWertOp` — how the partner is WRITTEN — and this reader's boundary is the same
        // one: a function call is not a bare entity spec, so neither side judges it.
        expected["a labelled enumeration against a string-valued FUNCTION of that field"] = emptyList()
        expected["a labelled enumeration read through a CATEGORY against a string field"] = emptyList()
        assertEquals(expected, actual, "what the text gate reads on the text side")
    }

    /** Only this family's two codes, so another family's finding cannot make a row look right. */
    private fun compare(probe: String): List<String> =
        checkRead(ModelSource(model(probe))).findings
            .filter { it.code in OWNED }
            .map { it.code }

    /** An insurance policy's tiers, mirroring the laws test's carrier. */
    private fun model(probe: String): String = """
        {"header":{"id":"policy-enum-display","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Plain","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"BASIC"},{"value":"PLUS"}]}}}},
            {"type":"Field","id":"f2","name":"PlainTwin","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"BASIC"},{"value":"PLUS"}]}}}},
            {"type":"Field","id":"f3","name":"Mirrored","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"BASIC","label":[{"locale":"en_US","text":"BASIC"}]},
                {"value":"PLUS","label":[{"locale":"en_US","text":"PLUS"}]}]}}}},
            {"type":"Field","id":"f4","name":"Labelled","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"BASIC","label":[{"locale":"en_US","text":"Basic cover"}]},
                {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}]}}}},
            {"type":"Field","id":"f5","name":"LabelledTwin","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"BASIC","label":[{"locale":"en_US","text":"Basic cover"}]},
                {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}]}}}},
            {"type":"Field","id":"f6","name":"TokenConflict","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"BASIC","label":[{"locale":"en_US","text":"Budget cover"}]},
                {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}]}}}},
            {"type":"Field","id":"f7","name":"DisplayConflict","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"ECONOMY","label":[{"locale":"en_US","text":"Basic cover"}]},
                {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}]}}}},
            {"type":"Field","id":"f8","name":"ForeignLabelled","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"BASIC","label":[{"locale":"fr_FR","text":"Budget cover"}]},
                {"value":"PLUS","label":[{"locale":"fr_FR","text":"Plus cover"}]}]}}}},
            {"type":"Field","id":"f9","name":"Categorized","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"BASIC","label":[{"locale":"en_US","text":"Budget cover"}]},
                {"value":"PLUS","label":[{"locale":"en_US","text":"Plus cover"}]}],
              "categories":[{"name":"Band","values":[{"value":"LOW"},{"value":"HIGH"}]}]}}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Holder) And $probe",
              "errorEntityRelPath":"../Holder",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        const val MIXED = "MVK_INVALID_COMPARE_ENUM_TO_STRING"
        const val CLASHING = "MVK_INVALID_COMPARE_ENUM_TO_ENUM"
        val OWNED = setOf(MIXED, CLASHING)
    }
}
