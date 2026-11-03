package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of KF589, replayed on both runtimes: a group's `indexFieldName` or `sortFields[].name` that lands
 * on a field whose short name A12 refuses draws the field-name sentence once more, beside whatever the ordering gates
 * draw; the gates themselves are withheld whenever the entity walk before them refused anything. A12's answer to
 * every row was measured by `adapter.laws.CheckerOrderingNameSentenceAgreementTest`; this class only replays it.
 */
class OrderingMemberFieldNameKf589Test {

    private fun codes(field: String, members: String, outer: String = "Channel", second: String = "Qty") = checkRead(ModelSource("""
        {"header":{"modelVersion":"29.4.0","id":"orders","locales":[{"code":"en_US"}]},"content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
         {"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
          {"type":"Group","id":"/Order/Items","name":"Items","Group":{"repeatability":5,$members"elements":[
           {"type":"Field","id":"/Order/Items/$field","name":"$field","Field":{"fieldType":{"type":"StringType","StringType":{}}}},
           {"type":"Field","id":"/Order/Items/$second","name":"$second","Field":{"fieldType":{"type":"StringType","StringType":{}}}}
          ]}},
          {"type":"Field","id":"/Order/$outer","name":"$outer","Field":{"fieldType":{"type":"StringType","StringType":{}}}}
         ]}}
        ]}}}
    """.trimIndent())).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private fun index(name: String) = "\"indexFieldName\":\"$name\","

    private fun sort(vararg names: String) =
        "\"sortFields\":[" + names.joinToString(",") { "{\"name\":\"$it\",\"order\":\"ASC\"}" } + "],"

    @Test
    fun aRefusedShortNameIsStatedOnceMoreForEachMember() {
        val bad = "Sk-u"
        val sentence = "RK_FIELD_NAME_ALPHABET"
        val actual = listOf(
            codes(bad, ""), codes(bad, index(bad)), codes(bad, sort(bad)),
            codes("Sku", index("../$bad"), outer = bad), codes("Sku", sort("../$bad"), outer = bad),
            codes("Sku", index("../Items/Sku")),
        )
        val expected = listOf(
            listOf("MVK_PATH_INVALID", sentence), listOf("MVK_PATH_INVALID", sentence, sentence),
            listOf("MVK_PATH_INVALID", sentence, sentence),
            listOf("MVK_PATH_INVALID", sentence, sentence), listOf("MVK_PATH_INVALID", sentence, sentence),
            emptyList(),
        )
        assertEquals(expected, actual)
    }

    /**
     * The duplicate verdict, which is no gate, skips only a refused name, and the sentence rides beside it.
     */
    @Test
    fun theSentenceRidesBesideTheGatesTheWalkLetsThrough() {
        val bad = "Sk-u"
        val sentence = "RK_FIELD_NAME_ALPHABET"
        val actual = listOf(
            codes(bad, sort(bad, bad)),
            codes("Sku", sort("Sku", "Sku", bad), second = bad),
            codes(bad, sort("Qty", "Qty2", "Qty3", bad)).filter { it == sentence },
        )
        val expected = listOf(
            listOf("MVK_PATH_INVALID", sentence, sentence),
            listOf("MVK_PATH_INVALID", "MVK_SORT_FIELD_DUPLICATE", sentence, sentence),
            // Only the field's own sentence: A12's metamodel holds three entries, so the fourth names nothing.
            listOf(sentence),
        )
        assertEquals(expected, actual)
    }

    private companion object {
    }
}
