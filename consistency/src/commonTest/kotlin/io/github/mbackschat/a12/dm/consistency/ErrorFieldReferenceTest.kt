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

    private val noWildcard = "MVK_NO_WILDCARD"

    /** A shipment model whose one rule carries [condition] and points at [errorFieldRelPath]. */
    private fun codes(errorFieldRelPath: String, condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"shipment","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shipment","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Customs","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Declaration","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Parcels","Group":{"repeatability":9,
                  "elements":[
                  {"type":"Field","id":"f2","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f4","name":"Volume","Field":{"fieldType":{"type":"NumberType"}}},
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
        actual["the target inside a HAVING filter only"] =
            codes("../Parcels/Weight", "Sum(Parcels*/Volume Having [Parcels/Weight] > 0) > 0")

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
        // A filter body's read is not the rule's own: A12 decides the gate over the rule's reads alone. The sum is
        // over a number, as in the kernel twin `ErrorFieldReferenceReachLawsTest`: summing text answers `MVK_NO_NUMBER` first (KF612).
        expected["the target inside a HAVING filter only"] = listOf(notReferenced)
        assertEquals(expected, actual, "which spellings count as referencing the error field")
    }

    /**
     * The shapes this family stays SILENT on, each for a stated reason rather than by omission.
     *
     * An unresolvable reference is
     * another family's finding and pre-empts this gate in A12 too. A condition naming no entity at all has
     * nothing to compare. And a condition that MENTIONS the target's name in a spelling the extractor did
     * not recognise is left alone, so a gap in the extractor can never become a refusal here.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a condition naming nothing that resolves"] =
            codes("../Carrier", "FieldFilled(NoSuchField)")
        actual["the rule's own group via the self-reference"] =
            codes("../Carrier", "GroupFilled(RuleGroup)")
        actual["the self-reference BESIDE an operand that does not reach"] =
            codes("../Carrier", "GroupFilled(RuleGroup) And FieldFilled(Customs/Declaration)")
        actual["the self-reference beside one that does not reach, target two levels down"] =
            codes("../Customs/Declaration", "GroupFilled(RuleGroup) And FieldFilled(Carrier)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a condition naming nothing that resolves"] = listOf("MVK_INVALID_ENTITY")
        // `RuleGroup` is a KEYWORD rather than an entity, so the condition names nothing and this family has
        // nothing to compare. A12 admits the rule — the error field must lie under the rule's group — so a
        // checker treating the spelling as an unresolved entity would refuse a legal model twice over.
        expected["the rule's own group via the self-reference"] = emptyList()
        // The rows the sole-self-reference one above cannot separate, and the reason this family reported on
        // two reference-corpus models the Kernel builds: with an ordinary operand beside it the condition
        // names something, so "nothing to compare" no longer explains the silence — only the self-reference's
        // own reach does. A12 admits both, at any depth
        // (`rulekit.validate.laws.ErrorFieldReferenceReachLawsTest`, measured 2026-09-13).
        expected["the self-reference BESIDE an operand that does not reach"] = emptyList()
        expected["the self-reference beside one that does not reach, target two levels down"] = emptyList()
        assertEquals(expected, actual, "the shapes the error-field gate deliberately does not report")
    }

    /**
     * An UNSTARRED read into a repetition the target does not sit in pre-empts this class.
     *
     * A12 answers that shape with `MVK_NO_WILDCARD` — it cannot say which repetition the error belongs to —
     * and a STAR on the same list collapses the iteration, so this family's own class answers there instead.
     * The pair is the discriminator: the target, the operand and the list are identical in both rows and only
     * the star moves.
     *
     * **This was a live wrong-class defect until 2026-09-14**, found by the mutation differential rather than
     * by this grid: every other row here holds the condition flat, so "the target is unreferenced" and
     * "nothing iterates" were one thing across all of them.
     *
     * **And the class A12 draws is now NAMED rather than merely yielded to**: [operandScopeContributions]
     * owns `MVK_NO_WILDCARD`, so the two unstarred rows carry it instead of reporting nothing. The pre-emption
     * this test locks is unchanged — what moved is that the report no longer goes silent on a model A12
     * refuses.
     */
    @Test
    fun anUnstarredRepetitionReadPreEmptsTheClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["UNSTARRED read, target outside the repetition"] =
            codes("../Carrier", "FieldFilled(Parcels/Weight)")
        actual["STARRED read of the same list"] = codes("../Carrier", "Sum(Parcels*/Weight) > 0")
        actual["UNSTARRED read beside a starred one"] =
            codes("../Carrier", "Sum(Parcels*/Weight) > 0 And FieldFilled(Parcels/Weight)")

        val expected = mutableMapOf<String, List<String>>()
        expected["UNSTARRED read, target outside the repetition"] = listOf(noWildcard)
        expected["STARRED read of the same list"] = listOf(notReferenced)
        // Per REFERENCE, not per condition: one unstarred read is enough to hand the verdict to A12's
        // iteration class, however many starred reads sit beside it.
        expected["UNSTARRED read beside a starred one"] = listOf(noWildcard)
        assertEquals(expected, actual, "an unstarred repetition read pre-empts the class")
    }

    /**
     * A star pre-empts the class only where it covers every level the read crosses: a star on the outer
     * level leaves the inner one iterating, and a star on the field repeats nothing, so the wildcard class
     * answers alone.
     */
    @Test
    fun aStarPreEmptsTheClassOnlyWhereItCoversEveryLevel() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the outer level starred, the inner bare"] = nestedCodes("Sum(Parcels*/Items/Qty) > 0")
        actual["the inner level starred, the outer bare"] = nestedCodes("Sum(Parcels/Items*/Qty) > 0")
        actual["both levels starred — the control"] = nestedCodes("Sum(Parcels*/Items*/Qty) > 0")
        actual["the outer level starred, REFERENCED target"] =
            nestedCodes("FieldFilled(Carrier) And Sum(Parcels*/Items/Qty) > 0")
        actual["a star on the field, its level bare"] = nestedCodes("Sum(Parcels/Weight*) > 0")
        actual["a star on a fixed group"] = codes("../Carrier", "FieldFilled(Customs*/Declaration)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the outer level starred, the inner bare"] = listOf(noWildcard)
        expected["the inner level starred, the outer bare"] = listOf(noWildcard)
        expected["both levels starred — the control"] = listOf(notReferenced)
        expected["the outer level starred, REFERENCED target"] = listOf(noWildcard)
        expected["a star on the field, its level bare"] = listOf("MVK_INVALID_WILDCARD")
        expected["a star on a fixed group"] = listOf("MVK_INVALID_WILDCARD")
        assertEquals(expected, actual, "which star pre-empts the unreferenced-target class")
    }

    /** The shipment with a repeatable `Items` nested in the parcels; the rule reports on `../Carrier`. */
    private fun nestedCodes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"shipment-nested","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shipment","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Parcels","Group":{"repeatability":9,
                  "elements":[
                  {"type":"Field","id":"f1","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g2","name":"Items","Group":{"repeatability":9,
                    "elements":[
                    {"type":"Field","id":"f2","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"${'$'}CONDITION","errorEntityRelPath":"../Carrier",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition),
        ),
    ).findings.map { it.code }

    /** The finding names the target, since the fix is to reference it or to point the rule elsewhere. */
    @Test
    fun theFindingNamesTheTargetItCouldNotFind() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"28.4.0","id":"shipment","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
