package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the category suffix an error-text parameter may carry.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ErrorTextCategorySuffixLawsTest`. The last row carries the risk: A12 answers the
 * MEMBERSHIP class there, so naming a category class would be the wrong one on a correct rejection.
 */
class ErrorTextCategorySuffixTest {

    @Test
    fun whichCategorySuffixAnErrorTextParameterMayCarry() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain parameter — the control"] = reporting("\$Reference\$")
        actual["an enum projected through a category it declares"] = reporting("\$Grade -> Band\$")
        actual["an enum projected through a category it does not"] = reporting("\$Grade -> NoSuch\$")
        actual["an enum that declares NO categories at all"] = reporting("\$Plain -> Band\$")
        actual["a NUMBER field with a category suffix"] = reporting("\$Amount -> Band\$")
        actual["a TEXT field with a category suffix"] = reporting("\$Note -> Band\$")
        actual["the arrow with no category name after it"] = reporting("\$Grade ->\$")
        actual["a field the condition never reads, with a valid suffix"] = reporting("\$Unread -> Band\$")
        actual["the INDEX terminal carrying a category suffix"] = reporting("\$index(Note)->Band\$")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain parameter — the control"] = emptyList()
        expected["an enum projected through a category it declares"] = emptyList()
        expected["an enum projected through a category it does not"] = listOf("INVALID_CATEGORY")
        // An enum with NO categories is the same class as one with other categories, measured.
        expected["an enum that declares NO categories at all"] = listOf("INVALID_CATEGORY")
        expected["a NUMBER field with a category suffix"] = listOf("FIELD_NO_ENUM")
        expected["a TEXT field with a category suffix"] = listOf("FIELD_NO_ENUM")
        expected["the arrow with no category name after it"] = listOf("NO_CATEGORY")
        // KF413 and KF501: the shared reader reaches membership before the category action.
        expected["a field the condition never reads, with a valid suffix"] = listOf("INVALID_FIELD")
        // A12 answers `FIELD_NO_ENUM` here too — for a STRING index field — but `checkIndexFieldRef` owns
        // this form and has eleven classes of its own, several about an ENUM index field. Agreeing by luck on
        // one carrier is not a basis, so this family declines the form outright.
        expected["the INDEX terminal carrying a category suffix"] = emptyList()
        assertEquals(expected, actual, "which category suffix an error-text parameter may carry")
    }

    private fun reporting(text: String): List<String> =
        checkRead(ModelSource(model(text))).findings.filter { it.code in OWNED }.map { it.code }

    /** An e-commerce catalogue desk mirroring the laws test's carrier. */
    private fun model(text: String): String = """
        {"header":{"id":"catalogue-error-text-category","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalogue","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f3","name":"Grade","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"NEW"},{"value":"USED"}],
              "categories":[{"name":"Band","values":[{"value":"NEW"},{"value":"USED"}]}]}}}},
            {"type":"Field","id":"f4","name":"Plain","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"NEW"},{"value":"USED"}]}}}},
            {"type":"Field","id":"f5","name":"Unread","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"ProbeRule","Rule":{
              "errorCondition":"FieldFilled(Reference) And FieldFilled(Note) And FieldFilled(Amount) And FieldFilled(Grade) And FieldFilled(Plain)",
              "errorEntityRelPath":"../Reference",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"$text"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf("NO_CATEGORY", "FIELD_NO_ENUM", "INVALID_CATEGORY", "INVALID_FIELD")
    }
}
