package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a computation may not read the field it writes".
 *
 * Mirrors `rulekit.validate.laws.ComputationSelfReadScopeLawsTest` on the same routing shape. The rows that
 * carry the risk are the ones that are NOT about the field itself: A12's predicate is containment, so the
 * computed field's own group and the root group above it are reads of it, while two shapes naming that very
 * group are exempt. A family keyed on path equality admits the first pair; one keyed on containment alone
 * refuses the second.
 *
 * The second grid is this checker's own silences, which the laws twin has no reason to carry: a NUMBER
 * target is left to the scale gate that answers ahead of this class, and a PROJECTED self-read is left to
 * the class that pre-empts it.
 */
class ComputationSelfReadTest {

    private val selfRead = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"

    @Test
    fun readingTheComputedFieldsGroupIsReadingTheField() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no guard at all — the liveness control"] = codes(inLegs("../Note", "[LegRef]", null))
        actual["a SIBLING field of the computed one"] =
            codes(inLegs("../Note", "[LegRef]", "FieldFilled(LegRef)"))
        actual["the computed field itself"] = codes(inLegs("../Note", "[LegRef]", "FieldFilled(Note)"))
        actual["GroupNotFilled over its own group"] =
            codes(inLegs("../Note", "[LegRef]", "GroupNotFilled(/Routing/Legs)"))
        actual["NumberOfFilledGroups over its own group"] =
            codes(inLegs("../Note", "[LegRef]", "NumberOfFilledGroups(/Routing/Legs*) > 0"))
        actual["GroupNotFilled over the ROOT — an ancestor like any other"] =
            codes(inLegs("../Note", "[LegRef]", "GroupNotFilled(/Routing)"))
        actual["GroupFilled over its own group — the context escape"] =
            codes(inLegs("../Note", "[LegRef]", "GroupFilled(/Routing/Legs)"))
        actual["CurrentRepetition of its own group"] =
            codes(inLegs("../Note", "[LegRef]", "CurrentRepetition(/Routing/Legs) == 1"))

        val expected = mutableMapOf<String, List<String>>()
        expected["no guard at all — the liveness control"] = emptyList()
        expected["a SIBLING field of the computed one"] = emptyList()
        expected["the computed field itself"] = listOf(selfRead)
        expected["GroupNotFilled over its own group"] = listOf(selfRead)
        expected["NumberOfFilledGroups over its own group"] = listOf(selfRead)
        expected["GroupNotFilled over the ROOT — an ancestor like any other"] = listOf(selfRead)
        expected["GroupFilled over its own group — the context escape"] = emptyList()
        expected["CurrentRepetition of its own group"] = emptyList()
        assertEquals(expected, actual, "which reads of the computed field's group count as reading it")
    }

    /**
     * The two silences this checker adds on top of A12's law, each because another class answers first.
     *
     * Both are under-reports and neither is a judgement call: the scale gate is measured ahead of this class
     * and of its projected sibling, and the suppression row is the one number case A12 measures the other
     * way — with the gate suppressed it reports this class whatever the derived scale is.
     */
    @Test
    fun theTwoShapesThisCheckerLeavesToTheClassThatAnswersFirst() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a NUMBER target reading itself"] =
            codes(inLegs("../Distance", "1", "FieldFilled(Distance)"))
        actual["the same with the scale gate suppressed"] =
            codes(inLegs("../Distance", "1", "FieldFilled(Distance)", suppressesTheScaleGate = true))
        actual["a STRING target read through a semantic index"] =
            codes(inRouting("../PlannedLeg", "[/Routing/Legs/LegRef For /Routing/PlannedLeg]"))
        actual["the same index keyed by a PEER — the control"] =
            codes(inRouting("../PlannedLeg", "[/Routing/Legs/LegRef For /Routing/PreferredLeg]"))

        val expected = mutableMapOf<String, List<String>>()
        expected["a NUMBER target reading itself"] = emptyList()
        expected["the same with the scale gate suppressed"] = listOf(selfRead)
        expected["a STRING target read through a semantic index"] = emptyList()
        expected["the same index keyed by a PEER — the control"] = emptyList()
        assertEquals(expected, actual, "the shapes another class answers first")
    }

    private fun codes(source: String): List<String> =
        check(ModelSource(source)).findings.filter { it.code == selfRead }.map { it.code }

    /** The routing sheet with one computation declared inside the repeatable `Legs` group. */
    private fun inLegs(
        target: String,
        operation: String,
        precondition: String?,
        suppressesTheScaleGate: Boolean = false,
    ): String = routing(
        inLegs = computation(target, operation, precondition, suppressesTheScaleGate),
        inRouting = "",
    )

    /** …and with one declared in the root group instead. */
    private fun inRouting(target: String, operation: String): String =
        routing(inLegs = "", inRouting = computation(target, operation, null, false))

    private fun computation(
        target: String,
        operation: String,
        precondition: String?,
        suppressesTheScaleGate: Boolean,
    ): String {
        val guard = precondition?.let { """"precondition":"$it",""" } ?: ""
        val suppression = if (suppressesTheScaleGate) {
            """"errorCodesToSuppress":["MVK_INVALID_COMPARE_DEC_PLACES"],"""
        } else {
            ""
        }
        return """
            ,{"type":"Computation","id":"c0","name":"LegComp","Computation":{
              "computedFieldRelPath":"$target",$suppression
              "errorMessage":[{"locale":"en_US","text":"x"}],
              "computationAlternatives":[{$guard"operation":"$operation"}]}}
        """.trimIndent()
    }

    /**
     * The routing shape, with the computation placed in whichever group the row needs.
     *
     * `Legs` declares an index field so a semantic index over it is authorable, which is what the projected
     * row needs; `PreferredLeg` is its non-target twin, so that row's control moves only the key.
     */
    private fun routing(inLegs: String, inRouting: String): String = """
        {"header":{"id":"routing","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Routing","Group":{"elements":[
            {"type":"Field","id":"f0","name":"PlannedLeg","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"PreferredLeg","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Legs","Group":{"repeatability":10,
              "indexFieldName":"LegRef","elements":[
                {"type":"Field","id":"f2","name":"LegRef","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f3","name":"Distance","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f4","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                $inLegs
            ]}}
            $inRouting
          ]}}
         ]}}}
    """.trimIndent()
}
