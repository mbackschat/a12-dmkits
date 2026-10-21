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
    private fun codes(groupName: String, fieldName: String = "Price"): List<String> = checkRead(
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
    private fun nestedCodes(groupName: String): List<String> = checkRead(
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

    /**
     * The one whole name A12 reserves, on the root carrier alone.
     *
     * Every row mirrors one measured by `rulekit.validate.laws.GroupNameAlphabetLawsTest`. The three
     * ADMITTED rows are the family's bound: the match is the whole name and the carrier is the root, so a
     * checker generalising either would refuse a model A12 takes.
     */
    @Test
    fun theReservedRootGroupName() {
        val reserved = listOf("MVK_ROOT_GROUP_NAME_INVALID")
        val actual = mutableMapOf<String, List<String>>()
        actual["Catalog — the control"] = codes("Catalog")
        actual["Id"] = codes("Id")
        actual["ID"] = codes("ID")
        actual["id"] = codes("id")
        actual["Identity — the name as a prefix"] = codes("Identity")
        actual["CatalogId — the name as a suffix"] = codes("CatalogId")
        actual["a NESTED group of that name"] = nestedCodes("Id")
        actual["a FIELD of that name"] = codes("Catalog", fieldName = "Id")

        val expected = mutableMapOf<String, List<String>>()
        expected["Catalog — the control"] = emptyList()
        expected["Id"] = reserved
        expected["ID"] = reserved
        expected["id"] = reserved
        expected["Identity — the name as a prefix"] = emptyList()
        expected["CatalogId — the name as a suffix"] = emptyList()
        expected["a NESTED group of that name"] = emptyList()
        expected["a FIELD of that name"] = emptyList()
        assertEquals(expected, actual, "the root-group name A12 reserves")
    }

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
        // KF315 and the R4 carrier population admit the German letter on groups, but refuse it on fields, where the
        // field-name sentence stands beside the class (KF580).
        expected["a GROUP with a non-ASCII letter"] = emptyList()
        expected["a FIELD with a non-ASCII letter"] = pathInvalid + "RK_FIELD_NAME_ALPHABET"
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
        // Beside each path class stands the carrier's own sentence (KF580): the root group's alphabet at the group, the
        // group-path one at the field inside it, the field-name one at a field.
        val refusedRoot = pathInvalid + "RK_ROOT_GROUP_NAME_ALPHABET" + "RK_GROUP_PATH_INVALID"
        for (character in listOf('-', ' ', '%', '@', '*')) {
            expected["a GROUP with `$character`"] = refusedRoot
            expected["a FIELD with `$character`"] = pathInvalid + "RK_FIELD_NAME_ALPHABET"
        }
        for (character in listOf('.', ':')) {
            expected["a GROUP with `$character`"] = refusedRoot
            expected["a FIELD with `$character`"] = listOf("MVK_NAME_INVALID_DOT_COLON_A12_DV")
        }
        // Not a bad name at all: the element's own path stops agreeing with the tree it is declared in.
        expected["a GROUP with the separator"] = inconsistent
        expected["a FIELD with the separator"] = inconsistent
        // The digit row parts on depth rather than on kind: the root group answers with its own class and
        // no path one, a group below it with the path class and no name one.
        expected["a ROOT group beginning with a digit"] = listOf("MVK_NAME_STARTS_WITH_DIGIT")
        expected["a NESTED group beginning with a digit"] = pathInvalid + "RK_GROUP_PATH_INVALID" + "RK_GROUP_PATH_INVALID"
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
     * A group below one named `xml…` draws the class at its own path, however it is named itself, and a field
     * below it draws nothing (KERNEL-FINDINGS KF558, `adapter.laws.CheckerClassMultiplicityAgreementTest`).
     */
    @Test
    fun theXmlPrefixReachesEveryGroupBelowIt() {
        val findings = checkRead(
            ModelSource(
                """
                {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Group","id":"g1","name":"xmlAddress","Group":{"elements":[
                      {"type":"Field","id":"f0","name":"Street","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Group","id":"g2","name":"Geo","Group":{"elements":[
                        {"type":"Group","id":"g3","name":"Point","Group":{"elements":[
                          {"type":"Field","id":"f1","name":"Lat","Field":{"fieldType":{"type":"StringType"}}}
                        ]}}
                      ]}}
                    ]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.code == "MVK_NAME_STARTS_WITH_XML" }.map { it.at }
        assertEquals(listOf("/Catalog/xmlAddress", "/Catalog/xmlAddress/Geo", "/Catalog/xmlAddress/Geo/Point"), findings)
    }

    /** An `xml` root its own path already refuses names no group below it, itself included (KF558). */
    @Test
    fun aRefusedPathCarriesNoXmlPrefixDownward() {
        val codes = checkRead(
            ModelSource(
                """
                {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"xmlCatalogé","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Title","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Shelf","Group":{"elements":[
                      {"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
                    ]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.justifiesRejection }.map { it.code }
        assertEquals(listOf("MVK_PATH_INVALID"), codes.filter { it.startsWith("MVK_") }.distinct())
    }

    /**
     * A RULE's name is asked none of this family's element-path classes, and that is measured rather than merely
     * unimplemented: an `xml` prefix on a rule is admitted, and a digit-initial or dotted rule name draws only
     * A12's codeless rule-name sentence, which the declaration-slot family publishes as `RK_RULE_NAME_ALPHABET`
     * (KERNEL-FINDINGS KF578; before KF420 this read as a build failure).
     */
    @Test
    fun aRulesOwnNameIsNotJudged() {
        val actual = mutableMapOf<String, List<String>>()
        for (name in listOf("Sound", "xmlRule", "1Rule", "Some.Rule")) {
            actual["a RULE named `$name`"] = checkRead(
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
            mapOf("Sound" to emptyList(), "xmlRule" to emptyList(), "1Rule" to listOf("RK_RULE_NAME_ALPHABET"),
                "Some.Rule" to listOf("RK_RULE_NAME_ALPHABET")).mapKeys { "a RULE named `${it.key}`" },
            actual,
            "a rule's own name is not judged",
        )
    }

    /** The finding locates the character, which is the only part an author cannot see in a long name. */
    @Test
    fun theFindingNamesTheCharacterAndItsOffset() {
        for ((group, field, character) in listOf(
            Triple("Cata-log", "Price", '-'),
            Triple("Cata/log", "Price", '/'),
            Triple("Catalog", "Pri/ce", '/'),
        )) {
            val finding = checkRead(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"$group","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"$field","Field":{"fieldType":{"type":"StringType"}}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ).findings.first { it.code.startsWith("MVK_") }
            assertEquals(if (character == '/') "MVK_PATHS_INCONSISTENT" else "MVK_PATH_INVALID", finding.code)
            val offset = if (group == "Catalog") 3 else 4
            assertEquals(true, finding.explain.contains("`$character` at offset $offset"), finding.explain)
        }
    }
}
