package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the error-field reference gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ErrorFieldReferenceReachLawsTest`. The ADMITTED rows carry the risk, and there are
 * many of them: a reference reaches its target under an absolute path, a wildcard step, a negated operator,
 * a comparison operand, and — the row a path comparison gets wrong — the enclosing GROUP.
 */
class ErrorFieldReferenceTest {

    private val notReferenced = "MVK_ERROR_FIELD_NOT_REFERENCED"

    /** A shipment model whose one rule carries [condition] and points at [errorFieldRelPath]. */
    private fun codes(errorFieldRelPath: String, condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"shipment","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shipment","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Customs","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Declaration","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Parcels","Group":{"repeatable":true,"maxRepetitions":9,
                  "elements":[
                  {"type":"Field","id":"f2","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f3","name":"Contents","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"${'$'}CONDITION","errorEntityRelPath":"${'$'}TARGET",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition).replace("\$TARGET", errorFieldRelPath),
        ),
    ).findings.map { it.code }

    @Test
    fun whichSpellingsCountAsReferencingTheErrorField() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the bare name — the control"] = codes("../Carrier", "FieldFilled(Carrier)")
        actual["an ABSOLUTE path to the same field"] = codes("../Carrier", "FieldFilled(/Shipment/Carrier)")
        actual["a sibling field instead"] = codes("../Carrier", "FieldFilled(Customs/Declaration)")
        actual["the target in the SECOND arm of an Or"] =
            codes("../Carrier", "FieldFilled(Customs/Declaration) Or FieldFilled(Carrier)")
        actual["the target under a NEGATED operator"] = codes("../Carrier", "FieldNotFilled(Carrier)")
        actual["a STARRED path to the same field"] =
            codes("../Parcels/Weight", "AtLeastOneFieldFilled(Parcels*/Weight)")
        actual["the enclosing GROUP rather than the field"] =
            codes("../Parcels/Weight", "GroupFilled(Parcels)")
        actual["a GROUP as the error target, with a field INSIDE it named"] =
            codes("../Parcels", "FieldFilled(Parcels/Weight)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the bare name — the control"] = emptyList()
        expected["an ABSOLUTE path to the same field"] = emptyList()
        expected["a sibling field instead"] = listOf(notReferenced)
        expected["the target in the SECOND arm of an Or"] = emptyList()
        expected["the target under a NEGATED operator"] = emptyList()
        expected["a STARRED path to the same field"] = emptyList()
        // The two containment rows, which run opposite ways: an ancestor reaches the target and a descendant
        // does not. Either one alone licenses the wrong implementation.
        expected["the enclosing GROUP rather than the field"] = emptyList()
        expected["a GROUP as the error target, with a field INSIDE it named"] = listOf(notReferenced)
        assertEquals(expected, actual, "which spellings count as referencing the error field")
    }

    /**
     * The shapes this family stays SILENT on, each for a stated reason rather than by omission.
     *
     * A filter's own reference does not satisfy A12's gate and is indistinguishable from an operand's once
     * the text is scanned, so it is treated as reaching — an under-report. An unresolvable reference is
     * another family's finding and pre-empts this gate in A12 too. A condition naming no entity at all has
     * nothing to compare. And a condition that MENTIONS the target's name in a spelling the extractor did
     * not recognise is left alone, so a gap in the extractor can never become a refusal here.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the target inside a HAVING filter only"] =
            codes("../Parcels/Weight", "Sum(Parcels*/Contents Having [Parcels/Weight] > 0) > 0")
        actual["a condition naming nothing that resolves"] =
            codes("../Carrier", "FieldFilled(NoSuchField)")
        actual["the rule's own group via the self-reference"] =
            codes("../Carrier", "GroupFilled(RuleGroup)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the target inside a HAVING filter only"] = emptyList()
        expected["a condition naming nothing that resolves"] = listOf("MVK_INVALID_ENTITY")
        // `RuleGroup` is a KEYWORD rather than an entity, so the condition names nothing and this family has
        // nothing to compare. A12 admits the rule — the error field must lie under the rule's group — so a
        // checker treating the spelling as an unresolved entity would refuse a legal model twice over.
        expected["the rule's own group via the self-reference"] = emptyList()
        assertEquals(expected, actual, "the shapes the error-field gate deliberately does not report")
    }

    /** The finding names the target, since the fix is to reference it or to point the rule elsewhere. */
    @Test
    fun theFindingNamesTheTargetItCouldNotFind() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"shipment","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Shipment","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Weight)","errorEntityRelPath":"../Carrier",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(notReferenced, finding.code)
        assertEquals("/Shipment/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Shipment/Carrier"), finding.explain)
    }
}
