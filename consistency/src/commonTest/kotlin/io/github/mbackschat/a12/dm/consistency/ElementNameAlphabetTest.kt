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
 * **The rows where the carriers DISAGREE are the point.** A single alphabet would have refused a field
 * named `_Slots`, which A12 admits, and stayed silent about a nested group named `_Slots`, which A12
 * refuses. There are THREE carriers, not two: a group's own laws part again on whether it is the ROOT group
 * ([KERNEL-FINDINGS KF322], `rulekit.validate.laws.DeclaredNameShapeLawsTest`), which admits a leading
 * underscore and answers a digit-initial name with its own class. Every divergence is locked here rather
 * than implied, because each one is a class A12 does not emit on the neighbouring carrier.
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

    /** …and the same pair with [groupName] a group NESTED inside the root one, which is a third carrier. */
    private fun nestedCodes(groupName: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Group","id":"g1","name":"${'$'}GROUP","Group":{"elements":[
                  {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}}
                ]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$GROUP", groupName),
        ),
    ).findings.map { it.code }

    private val underscore = listOf("NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN")
    private val pathInvalid = listOf("MVK_PATH_INVALID")
    private val inconsistent = listOf("MVK_PATHS_INCONSISTENT")

    @Test
    fun theTwoCarriersAreAskedTheirOwnQuestion() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a well-formed pair — the control"] = codes("Catalog")
        actual["a NESTED group with a leading underscore"] = nestedCodes("_Address")
        actual["a ROOT group with a leading underscore"] = codes("_Catalog")
        actual["a FIELD with a leading underscore"] = codes("Catalog", "_Price")
        actual["a NESTED group named __meta"] = nestedCodes("__meta")
        actual["a NESTED group named __attachment_meta_photo"] = nestedCodes("__attachment_meta_photo")
        actual["a NESTED group named __other"] = nestedCodes("__other")
        actual["a ROOT group named __other"] = codes("__other")
        actual["a GROUP with a non-ASCII letter"] = codes("Katalög")
        actual["a FIELD with a non-ASCII letter"] = codes("Catalog", "Preis_ö")

        val expected = mutableMapOf<String, List<String>>()
        expected["a well-formed pair — the control"] = emptyList()
        // The divergence, in three directions on one member: A12 reserves the prefix on a group BELOW the
        // root, admits it on the root group, and admits it on a field.
        expected["a NESTED group with a leading underscore"] = underscore
        expected["a ROOT group with a leading underscore"] = emptyList()
        expected["a FIELD with a leading underscore"] = emptyList()
        expected["a NESTED group named __meta"] = emptyList()
        expected["a NESTED group named __attachment_meta_photo"] = emptyList()
        expected["a NESTED group named __other"] = underscore
        expected["a ROOT group named __other"] = emptyList()
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
        actual["a ROOT group beginning with a digit"] = codes("1Catalog")
        actual["a NESTED group beginning with a digit"] = nestedCodes("1Address")
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
        // The digit row parts on depth rather than on kind: the root group answers with its own class and
        // no path one, a group below it with the path class and no name one.
        expected["a ROOT group beginning with a digit"] = listOf("MVK_NAME_STARTS_WITH_DIGIT")
        expected["a NESTED group beginning with a digit"] = pathInvalid
        // A12 reports BOTH classes for this shape on the field carrier, so both are stated.
        expected["a FIELD beginning with a digit"] = pathInvalid + "MVK_NAME_STARTS_WITH_DIGIT_A12_DV"
        assertEquals(expected, actual, "which code each illegal character draws on each carrier")
    }

    /**
     * The `xml` prefix, which A12 reserves on both kinds of element and spells differently on each.
     *
     * The mixed-case and bare-`xml` rows are measured on every carrier rather than transferred from the
     * field: this law's neighbours part on the carrier twice over, so a case rule confirmed on one of them
     * is not evidence about the others — and refusing `XmlPrice` on a carrier that admits it would be an
     * over-rejection reached by analogy.
     */
    @Test
    fun theReservedXmlPrefixOnEachCarrier() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a FIELD starting with `xml`"] = codes("Catalog", "xmlPrice")
        actual["a FIELD starting with `XML`"] = codes("Catalog", "XMLPrice")
        actual["a FIELD starting with `Xml`"] = codes("Catalog", "XmlPrice")
        actual["a FIELD named exactly `xml`"] = codes("Catalog", "xml")
        actual["a FIELD starting with `xm`"] = codes("Catalog", "xmPrice")
        actual["a FIELD merely CONTAINING xml"] = codes("Catalog", "PriceXmlId")
        actual["a ROOT group starting with `xml`"] = codes("xmlCatalog")
        actual["a ROOT group starting with `Xml`"] = codes("XmlCatalog")
        actual["a NESTED group starting with `XML`"] = nestedCodes("XMLAddress")
        actual["a NESTED group named exactly `xml`"] = nestedCodes("xml")
        actual["a NESTED group merely CONTAINING xml"] = nestedCodes("AddressXmlId")

        val expected = mutableMapOf<String, List<String>>()
        // The field carrier's spelling of the class is the suffixed one; both group carriers' is the bare one.
        expected["a FIELD starting with `xml`"] = listOf("MVK_NAME_STARTS_WITH_XML_A12_DV")
        expected["a FIELD starting with `XML`"] = listOf("MVK_NAME_STARTS_WITH_XML_A12_DV")
        expected["a FIELD starting with `Xml`"] = listOf("MVK_NAME_STARTS_WITH_XML_A12_DV")
        expected["a FIELD named exactly `xml`"] = listOf("MVK_NAME_STARTS_WITH_XML_A12_DV")
        expected["a FIELD starting with `xm`"] = emptyList()
        expected["a FIELD merely CONTAINING xml"] = emptyList()
        expected["a ROOT group starting with `xml`"] = listOf("MVK_NAME_STARTS_WITH_XML")
        expected["a ROOT group starting with `Xml`"] = listOf("MVK_NAME_STARTS_WITH_XML")
        expected["a NESTED group starting with `XML`"] = listOf("MVK_NAME_STARTS_WITH_XML")
        expected["a NESTED group named exactly `xml`"] = listOf("MVK_NAME_STARTS_WITH_XML")
        expected["a NESTED group merely CONTAINING xml"] = emptyList()
        assertEquals(expected, actual, "the reserved `xml` prefix on each carrier")
    }

    /**
     * A RULE's name is asked nothing, and that is measured rather than merely unimplemented.
     *
     * A digit-initial or dotted rule name leaves A12 unable to BUILD the model, so no class exists for this
     * family to reproduce there, and an `xml` prefix on a rule is simply admitted.
     */
    @Test
    fun aRulesOwnNameIsNotJudged() {
        val actual = mutableMapOf<String, List<String>>()
        for (name in listOf("Sound", "xmlRule", "1Rule", "Some.Rule")) {
            actual["a RULE named `$name`"] = check(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Rule","id":"r0","name":"${'$'}NAME","Rule":{
                          "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
                          "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                      ]}}
                     ]}}}
                    """.trimIndent().replace("\$NAME", name),
                ),
            ).findings.map { it.code }
        }

        assertEquals(
            listOf("Sound", "xmlRule", "1Rule", "Some.Rule").associate { "a RULE named `$it`" to emptyList<String>() },
            actual,
            "a rule's own name is not judged",
        )
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
