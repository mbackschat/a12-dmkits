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

    private val projected = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD"

    /**
     * The number target read through a semantic index whose key is a string literal.
     *
     * NOT a raw string: the model is assembled as one, and a raw string passes `\\` through as two
     * backslashes, which breaks the JSON and empties the fixture without failing anything.
     */
    private val indexed = "[/Routing/Legs/Distance For \\\"L-1\\\"]"

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
     * An exemption belongs to the OPERAND, not to the group it spells — mirrors
     * `rulekit.validate.laws.ComputationSelfReadScopeLawsTest.anExemptOperandExemptsOnlyItself`.
     */
    @Test
    fun anExemptOperandExemptsOnlyItself() {
        fun guarded(precondition: String) = codes(inLegs("../Note", "[LegRef]", precondition))
        val actual = linkedMapOf(
            "GroupFilled beside GroupNotFilled over the same group" to
                guarded("GroupFilled(/Routing/Legs) And GroupNotFilled(/Routing/Legs)"),
            "CurrentRepetition beside GroupNotFilled over the same group" to
                guarded("CurrentRepetition(/Routing/Legs) == 1 And GroupNotFilled(/Routing/Legs)"),
            "AtLeastOneGroupFilled over the root beside GroupNotFilled over the root" to
                guarded("AtLeastOneGroupFilled(/Routing) And GroupNotFilled(/Routing)"),
            "GroupFilled beside a sibling field — the control" to
                guarded("GroupFilled(/Routing/Legs) And FieldFilled(LegRef)"),
        )
        assertEquals(linkedMapOf(
            "GroupFilled beside GroupNotFilled over the same group" to listOf(selfRead),
            "CurrentRepetition beside GroupNotFilled over the same group" to listOf(selfRead),
            "AtLeastOneGroupFilled over the root beside GroupNotFilled over the root" to listOf(selfRead),
            "GroupFilled beside a sibling field — the control" to emptyList(),
        ), actual, "an exempt operand against the same group read beside it")
    }

    /**
     * The scale gate's bound, and the PROJECTED half that shares it.
     *
     * The gate is measured ahead of both classes, so a NUMBER target is left alone; the suppression rows are
     * the two cases A12 settles the other way, each measured on its own class rather than transferred from
     * the other. The projected rows are the pre-emption: a projection of the computed field is A12's other
     * class, and it answers wherever it appears in the computation.
     */
    @Test
    fun theScaleGatesBoundAndTheProjectedHalfThatSharesIt() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a NUMBER target reading itself"] =
            codes(inLegs("../Distance", "1", "FieldFilled(Distance)"))
        actual["the same PLAIN read with the scale gate suppressed"] =
            codes(inLegs("../Distance", "1", "FieldFilled(Distance)", suppressesTheScaleGate = true))
        actual["a STRING target read through a semantic index keyed by itself"] =
            codes(inRouting("../PlannedLeg", "[/Routing/Legs/LegRef For /Routing/PlannedLeg]"))
        actual["the same index keyed by a PEER — the control"] =
            codes(inRouting("../PlannedLeg", "[/Routing/Legs/LegRef For /Routing/PreferredLeg]"))
        actual["a NUMBER target read through a semantic index"] = codes(inLegs("../Distance", indexed, null))
        actual["the same PROJECTED read with the scale gate suppressed"] =
            codes(inLegs("../Distance", indexed, null, suppressesTheScaleGate = true))
        actual["a projected read in the GUARD of a plain self-read"] =
            codes(inRouting("../PlannedLeg", "[/Routing/PreferredLeg]",
                "FieldFilled(/Routing/Legs/LegRef For /Routing/PlannedLeg)"))

        val expected = mutableMapOf<String, List<String>>()
        expected["a NUMBER target reading itself"] = emptyList()
        expected["the same PLAIN read with the scale gate suppressed"] = listOf(selfRead)
        expected["a STRING target read through a semantic index keyed by itself"] = listOf(projected)
        expected["the same index keyed by a PEER — the control"] = emptyList()
        expected["a NUMBER target read through a semantic index"] = emptyList()
        expected["the same PROJECTED read with the scale gate suppressed"] = listOf(projected)
        expected["a projected read in the GUARD of a plain self-read"] = listOf(projected)
        assertEquals(expected, actual, "the scale gate's bound and the projected half")
    }

    /**
     * The projected class is the calculation module's, drawn once the computation parsed, so a precondition
     * that does not parse or resolve withholds it — `rulekit.validate.laws.ComputedTargetProjectedReadLawsTest
     * .aParseTimeClassInAPreconditionWithholdsTheProjectedClass`. Read off the whole declaration, because the
     * class that stands is another family's.
     */
    @Test
    fun aParseTimeClassInAPreconditionWithholdsTheProjectedClass() {
        val keyedByTheTarget = "[/Routing/Legs/LegRef For /Routing/PlannedLeg]"
        fun declared(precondition: String) =
            checkRead(ModelSource(inRouting("../PlannedLeg", keyedByTheTarget, precondition))).findings
                .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Routing/LegComp" }
                .map { it.code }
        assertEquals(listOf("MVK_NO_NUMBER"), declared("RoundUpValue(/Routing/PreferredLeg, 2) > 0"))
        assertEquals(listOf("MVK_INVALID_ENTITY"), declared("FieldFilled(NoSuchField)"))
    }

    private fun codes(source: String): List<String> =
        checkRead(ModelSource(source)).findings.filter { it.code in setOf(selfRead, projected) }.map { it.code }

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
    private fun inRouting(target: String, operation: String, precondition: String? = null): String =
        routing(inLegs = "", inRouting = computation(target, operation, precondition, false))

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
