package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of an Enumeration declaration's notification stream (KERNEL-FINDINGS KF551), on JVM and JS.
 *
 * Every row is one `adapter.laws.CheckerEnumDeclarationStreamAgreementTest` compares with Kernel 30.8.1: invalid
 * characters per UTF-16 unit first, then every category's unclassified values, every surplus token, the spoilt
 * pairings and the value duplicates, each identical notification once, and no coded finding for an entry
 * carrying a supplementary-plane character, which draws the unsupported-sign class instead.
 */
class EnumDeclarationStreamTest {

    private val bad = "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER"
    private val missing = "MVK_MISSING_CATEGORY_VALUE"
    private val surplus = "MVK_MISSING_INTERNAL_VALUE"
    private val catDup = "MVK_CATEGORY_VALUE_DUPLICATE"
    private val intDup = "MVK_INTERNAL_VALUES_DUPLICATE"
    private val signs = "RK_UNSUPPORTED_CHARACTER"

    /** U+1F6D2, a surrogate pair. */
    private val cart = "" + Char(0xd83d) + Char(0xded2)

    @Test
    fun shortfallsPrecedeSurplusesAcrossCategories() = assertEquals(
        listOf(missing, surplus, surplus),
        codes(listOf("BASIC", "PLUS"), listOf(listOf("STANDARD", "HIGH", "TOP"), listOf("RETAIL"), listOf("N", "S", "E"))),
    )

    @Test
    fun identicalNotificationsAreReportedOnce() {
        assertEquals(listOf(missing, catDup, intDup, "RK_ENUM_VALUE_DUPLICATE"), codes(listOf("BASIC", "PLUS", "PLUS"), listOf(listOf("STANDARD"))))
        assertEquals(listOf(surplus), codes(listOf("BASIC"), listOf(listOf("STANDARD", "HIGH", "HIGH"))))
    }

    @Test
    fun invalidCharactersComeFirstAndCountEachUnit() = assertEquals(
        listOf(bad, bad, bad, missing),
        codes(listOf("o$cart", "BASIC"), listOf(listOf("STANDARD")), restricted = true).filterNot { it == signs },
    )

    @Test
    fun anEntryCarryingASignIsNamedByNoCodedClass() {
        assertEquals(listOf(signs, "RK_ENUM_VALUE_DUPLICATE"), codes(listOf("BASIC", cart, cart), listOf(listOf("STANDARD"))))
        assertEquals(listOf(signs), codes(listOf("BASIC"), listOf(listOf("STANDARD", cart))))
    }

    private fun codes(values: List<String>, categories: List<List<String>>, restricted: Boolean = false): List<String> =
        checkRead(ModelSource(model(values, categories, restricted))).findings
            .filter { it.at == "/Store/Tier" }.map { it.code }

    private fun model(values: List<String>, categories: List<List<String>>, restricted: Boolean): String {
        val valueJson = values.joinToString(",") { """{"value":"$it"}""" }
        val categoryJson = categories.mapIndexed { index, tokens ->
            """{"name":"Band$index","values":[${tokens.joinToString(",") { "\"$it\"" }}]}"""
        }.joinToString(",")
        val config = if (restricted) ""","modelConfig":{"supportedCharacters":["A-Z"]}""" else ""
        return """
            {"header":{"id":"store","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Store","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[$valueJson],"categories":[$categoryJson]}}}}
              ]}}
             ]}$config}}
        """.trimIndent()
    }
}
