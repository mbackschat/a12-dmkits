package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of [KERNEL-FINDINGS KF315] — a declared name's alphabet, which is TWO laws.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.GroupNameAlphabetLawsTest` and `FieldNameAlphabetLawsTest`, whose character sweeps
 * cover every printable ASCII character outside the admitted alphabet on both carriers.
 *
 * **The rows where the two carriers DISAGREE are the point.** A single alphabet would have refused a field
 * named `_Slots`, which A12 admits, and stayed silent about a group named `_Slots`, which A12 refuses. Both
 * appear here side by side so the divergence is locked rather than implied.
 */
class ElementNameAlphabetTest {

    /** A model whose one group holds one field, both named as given. */
    private fun codes(groupName: String, fieldName: String = "Price"): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"${'$'}GROUP","Group":{"elements":[
                {"type":"Field","id":"f0","name":"${'$'}FIELD","Field":{"fieldType":{"type":"StringType"}}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$GROUP", groupName).replace("\$FIELD", fieldName),
        ),
    ).findings.map { it.code }

    private val pathInvalid = listOf("MVK_PATH_INVALID")
    private val inconsistent = listOf("MVK_PATHS_INCONSISTENT")

    @Test
    fun theTwoCarriersAreAskedTheirOwnQuestion() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a well-formed pair — the control"] = codes("Catalog")
        actual["a GROUP with a leading underscore"] = codes("_Catalog")
        actual["a FIELD with a leading underscore"] = codes("Catalog", "_Price")
        actual["a GROUP named __meta"] = codes("__meta")
        actual["a GROUP named __attachment_meta_photo"] = codes("__attachment_meta_photo")
        actual["a GROUP named __other"] = codes("__other")
        actual["a GROUP with a non-ASCII letter"] = codes("Katalög")
        actual["a FIELD with a non-ASCII letter"] = codes("Catalog", "Preis_ö")

        val expected = mutableMapOf<String, List<String>>()
        expected["a well-formed pair — the control"] = emptyList()
        // The divergence, in both directions on one member.
        expected["a GROUP with a leading underscore"] = listOf("NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN")
        expected["a FIELD with a leading underscore"] = emptyList()
        expected["a GROUP named __meta"] = emptyList()
        expected["a GROUP named __attachment_meta_photo"] = emptyList()
        expected["a GROUP named __other"] = listOf("NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN")
        // Silent on BOTH carriers, and for different reasons: A12 admits it on a group, and on a field only
        // one such character has been measured — so refusing the class would rest a rejection on one row.
        expected["a GROUP with a non-ASCII letter"] = emptyList()
        expected["a FIELD with a non-ASCII letter"] = emptyList()
        assertEquals(expected, actual, "the two carriers' own name laws")
    }

    @Test
    fun eachIllegalCharacterDrawsTheCodeItsCarrierGives() {
        val actual = mutableMapOf<String, List<String>>()
        for (character in listOf('-', ' ', '%', '@', '*')) {
            actual["a GROUP with `$character`"] = codes("Cata${character}log")
            actual["a FIELD with `$character`"] = codes("Catalog", "Pri${character}ce")
        }
        // The two characters that answer to their own validator on a FIELD and to the path one on a GROUP.
        for (character in listOf('.', ':')) {
            actual["a GROUP with `$character`"] = codes("Cata${character}log")
            actual["a FIELD with `$character`"] = codes("Catalog", "Pri${character}ce")
        }
        actual["a GROUP with the separator"] = codes("Cata/log")
        actual["a FIELD with the separator"] = codes("Catalog", "Pri/ce")
        actual["a GROUP beginning with a digit"] = codes("1Catalog")
        actual["a FIELD beginning with a digit"] = codes("Catalog", "1Price")

        val expected = mutableMapOf<String, List<String>>()
        for (character in listOf('-', ' ', '%', '@', '*')) {
            expected["a GROUP with `$character`"] = pathInvalid
            expected["a FIELD with `$character`"] = pathInvalid
        }
        for (character in listOf('.', ':')) {
            expected["a GROUP with `$character`"] = pathInvalid
            expected["a FIELD with `$character`"] = listOf("MVK_NAME_INVALID_DOT_COLON_A12_DV")
        }
        // Not a bad name at all: the element's own path stops agreeing with the tree it is declared in.
        expected["a GROUP with the separator"] = inconsistent
        expected["a FIELD with the separator"] = inconsistent
        expected["a GROUP beginning with a digit"] = pathInvalid
        expected["a FIELD beginning with a digit"] = pathInvalid
        assertEquals(expected, actual, "which code each illegal character draws on each carrier")
    }

    /** The finding locates the character, which is the only part an author cannot see in a long name. */
    @Test
    fun theFindingNamesTheCharacterAndItsOffset() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Cata-log","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals("MVK_PATH_INVALID", finding.code)
        assertEquals(true, finding.explain.contains("`-` at offset 4"), finding.explain)
    }
}
