package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of KF589, replayed on both runtimes: a group's `indexFieldName` or `sortFields[].name` that lands
 * on a field whose short name A12 refuses draws the field-name sentence once more, in place of the outside and type
 * codes. A12's answer to every row was measured by `adapter.laws.CheckerOrderingNameSentenceAgreementTest`; this
 * class only replays it.
 */
class OrderingMemberFieldNameKf589Test {

    private fun codes(field: String, members: String, outer: String = "Channel") = checkRead(ModelSource("""
        {"header":{"id":"orders","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
         {"type":"Group","id":"/Order","name":"Order","Group":{"elements":[
          {"type":"Group","id":"/Order/Items","name":"Items","Group":{"repeatability":5,$members"elements":[
           {"type":"Field","id":"/Order/Items/$field","name":"$field","Field":{"fieldType":{"type":"StringType","StringType":{}}}}
          ]}},
          {"type":"Field","id":"/Order/$outer","name":"$outer","Field":{"fieldType":{"type":"StringType","StringType":{}}}}
         ]}}
        ]}}}
    """.trimIndent())).findings.filter { it.justifiesRejection }.map { it.code }.sorted()

    private fun index(name: String) = "\"indexFieldName\":\"$name\","

    private fun sort(name: String) = "\"sortFields\":[{\"name\":\"$name\",\"order\":\"ASC\"}],"

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
}
