package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of KF583's three element-name laws, replayed on both runtimes: a computed field whose path
 * carries a dot or a colon, an underscore group below an `xml…` ancestor, and a blank name. A12's answer to every
 * row was measured by `adapter.laws.CheckerComputedPathAgreementTest`; this class only replays it.
 */
class ElementNameKf583Test {

    /** `/[root]{[part]{Bin{Slot, Label, computation Fill -> Label}}}`, with an optional rule name and Bin index. */
    private fun codes(root: String = "Order", part: String = "Part", bin: String = "Bin", label: String = "Label",
                      fill: String = "Fill", indexed: Boolean = false, operation: String = "\"bin\""): List<String> {
        val r = "/$root"; val p = "$r/$part"; val b = "$p/$bin"
        val index = if (indexed) ""","repeatability":5,"indexFieldName":"$label"""" else ""
        return checkRead(ModelSource("""
            {"header":{"modelVersion":"28.4.0","id":"orders","locales":[{"code":"en_US"}]},"content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
             {"type":"Group","id":"$r","name":"$root","Group":{"elements":[
              {"type":"Group","id":"$p","name":"$part","Group":{"elements":[
               {"type":"Group","id":"$b","name":"$bin","Group":{"elements":[
                {"type":"Field","id":"$b/Slot","name":"Slot","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"$b/$label","name":"$label","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Computation","id":"$b/$fill","name":"$fill","Computation":{"computedFieldRelPath":"../$label",
                 "commonPrecondition":"FieldFilled(Slot)","computationAlternatives":[
                  {"precondition":"FieldFilled(Slot)","operation":${operation.quoted()}}],
                 "errorMessage":[{"locale":"en_US","text":"Label from the slot"}]}}
               ]$index}}
              ]}}
             ]}}
            ]}}}
        """.trimIndent())).findings.filter { it.justifiesRejection }.map { it.code }
            .filter { it != RK_GROUP_PATH_INVALID }.sorted()
    }

    private fun String.quoted() = "\"" + replace("\"", "\\\"") + "\""

    @Test
    fun aComputedPathCarryingADotOrAColonIsRefusedAfterTheIndexGate() {
        val actual = linkedMapOf(
            "control" to codes(),
            "a dotted part" to codes(part = "Co.de"),
            "a colon in the root" to codes(root = "Co:de"),
            "a dotted computed field" to codes(label = "La.bel"),
            "a dotted part beside an index target" to codes(part = "Co.de", indexed = true),
            "a dotted part beside a refused operation" to codes(part = "Co.de", operation = "[Nope]"),
        )
        val expected = linkedMapOf(
            "control" to emptyList(),
            "a dotted part" to listOf(FIELDNAME, "MVK_PATH_INVALID"),
            "a colon in the root" to listOf(FIELDNAME, "MVK_PATH_INVALID", "RK_ROOT_GROUP_NAME_ALPHABET"),
            "a dotted computed field" to listOf(FIELDNAME, "MVK_NAME_INVALID_DOT_COLON_A12_DV"),
            "a dotted part beside an index target" to listOf("MVK_ERROR_INDEX_AS_CALCULATED_FIELD", "MVK_PATH_INVALID"),
            "a dotted part beside a refused operation" to listOf("MVK_INVALID_ENTITY", "MVK_PATH_INVALID"),
        )
        assertEquals<Map<String, List<String>>>(expected, actual)
    }

    @Test
    fun anUnderscoreGroupBelowAnXmlAncestorDrawsBothClasses() {
        assertEquals(listOf(XML, XML, UNDERSCORE), codes(part = "xmlCode", bin = "_Bin"))
        assertEquals(listOf(XML, UNDERSCORE), codes(part = "_Code", bin = "xmlBin"))
    }

    @Test
    fun aBlankNameLeavesTheModelUnbuiltAndANoBreakSpaceDoesNot() {
        for (blank in listOf("", " ", "\t", "\n", "\u2003")) {
            assertEquals(true, UNBUILT in codes(bin = blank), "a bin named ${blank.map { it.code }}")
            assertEquals(true, UNBUILT in codes(fill = blank), "a computation named ${blank.map { it.code }}")
        }
        assertEquals(false, UNBUILT in codes(bin = "\u00A0"), "a no-break space is not blank")
    }

    private companion object {
        const val FIELDNAME = "MVK_ERROR_INVALID_FIELDNAME"
        const val XML = "MVK_NAME_STARTS_WITH_XML"
        const val UNDERSCORE = "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN"
        const val UNBUILT = "CK_MODEL_NOT_BUILDABLE"
    }
}
