package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's law on an enumeration's `sValue`.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EnumDeclarationShapeLawsTest`. The blank row is the one that matters: A12 reads an
 * empty member as unset, so a checker comparing it against the domain would refuse a model A12 admits.
 */
class EnumSpecialValueTest {

    /** A catalog whose one enumeration carries [body] as its `EnumerationType`. */
    private fun codes(body: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{$body}}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private val twoValues = """"values":[{"value":"BASIC"},{"value":"PLUS"}]"""

    @Test
    fun theSpecialValueMustBeOneOfTheDeclaredOnes() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no sValue — the control"] = codes(twoValues)
        actual["an sValue naming a declared value"] = codes("""$twoValues,"sValue":"BASIC"""")
        actual["an sValue naming NO declared value"] = codes("""$twoValues,"sValue":"NOPE"""")
        actual["a BLANK sValue"] = codes("""$twoValues,"sValue":"""" + "\"")

        val expected = mutableMapOf<String, List<String>>()
        expected["no sValue — the control"] = emptyList()
        expected["an sValue naming a declared value"] = emptyList()
        expected["an sValue naming NO declared value"] = listOf("MVK_S_VALUE_INVALID")
        expected["a BLANK sValue"] = emptyList()
        assertEquals(expected, actual, "an enumeration's special value against its domain")
    }
}
