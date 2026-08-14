package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — **`FirstFilledValue` over a group operand from a DEEP rule row**, the sibling site a
 * cold review found unswept after the validation fill-quantifier half was fixed.
 *
 * **Why this file exists.** `Document.fillScope` and `Document.containedFieldCells` are two implementations of one
 * idea, and the earlier fix capped the bound prefix in the first only. The second still computed
 * `commonPrefixLen(fieldParts, evaluatingGroupParts)` uncapped, and it has two reachable callers the earlier
 * differential cannot touch: the compute-path bare-group fill scan and **`FirstFilledValue`'s group operand**.
 * Root-cause generalization is the standing rule — sweep every sibling site before declaring the class fixed — and
 * this is the sweep that was missing.
 *
 * **Two constraints forced this shape, and the second was measured rather than reasoned.** First, the pin exists
 * only when the rule's group is a repeatable level strictly BELOW the operand: `groupScopePrefix` takes
 * `rowReps.take(minOf(commonPrefixLen(...), cap))`, so a disjoint or shallower rule group makes `commonPrefixLen`
 * the binding term and the cap never applies. Second, the natural compute-path version of this test is
 * **inadmissible** — a computation whose target sits inside its own operand draws
 * `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` ("the calculated field cannot be used in prerequisites and calculation
 * functions"), and since the target must live in the computation's group and that group must be below the operand,
 * there is no legal compute spelling of this discriminator. A RULE's error field carries no such restriction, so
 * the validation caller is the one that can be measured at all.
 *
 * **The discriminator is a per-row FIRE SET.** With the direct field empty and both nested rows filled, the
 * operand's first filled value is nested row 1's — the same answer from every row — while a read pinned to the
 * rule's own row answers each row with its own cell:
 *
 * | reading | row 1 fires on `== "AAA"` | row 2 fires on `== "AAA"` |
 * |---|---|---|
 * | operand-scoped (correct) | yes | yes — same operand, same answer |
 * | pinned to the rule's own row | yes | **no** — its own cell is `"BBB"` |
 */
class ComputeGroupScopeFromADeepRowDiffTest {

    private companion object {
        const val REF = "/Doc/Reference"
        const val HANDLING = "/Doc/Handling"
        const val PRIMARY_NOTE = "/Doc/Handling/PrimaryNote"
        const val INSPECTIONS = "/Doc/Handling/Inspections"
        const val FINDING = "/Doc/Handling/Inspections/Finding"
        const val CODE = "CGSD"
    }

    /**
     * `Handling` holds one direct String and a nested repeatable `Inspections` carrying one String. Exactly one
     * field per level: the rule's own error-field reference is what anchors iteration on `Inspections`, so no extra
     * field is needed, and an always-filled extra would dilute the expansion the discriminator depends on.
     */
    private val base = DocumentModelDraft.named("deep-first-filled").locale("en_US")
        .rootGroup("Doc") { g ->
            g.stringField("Reference") { }
            g.group("Handling") { h ->
                h.stringField("PrimaryNote") { }
                h.group("Inspections") { i -> i.repeatable(2).stringField("Finding") { } }
            }
        }.build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    /** The rule sits ON the nested repeatable level and reports at its own `Finding`, so the fire set is per-row. */
    private fun agreeFireRows(condition: String, expected: List<String>, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, INSPECTIONS, "R", "../Finding", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(kernelMessages.map { it.code() }.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape " +
                "(${kernelMessages.map { it.code() }.filter { it.startsWith("MVK_") }})"
        }
        val groovy = kernelMessages.filter { it.code() == CODE }.map { it.field() }.sorted()

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US)
            .filter { it.code() == CODE }.map { it.field() }.sorted()

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc)
            .filter { it.code == CODE }.map { it.errorPath }.sorted()

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /** The direct field is EMPTY and the two nested rows hold different values, so row order decides the answer. */
    private fun twoDistinctNestedFindings(b: Both) {
        b.group("/Doc", 1).field(REF, "D-1", 1, 1)
            .group(INSPECTIONS, 1, 1, 1).field(FINDING, "AAA", 1, 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 2).field(FINDING, "BBB", 1, 1, 2, 1)
    }

    /**
     * **THE DISCRIMINATOR.** The operand's first filled value is nested row 1's `AAA` from EVERY row, so both rows
     * fire. A scan pinned to the rule's own row answers row 2 with its own `BBB`, and row 2 stops firing.
     */
    @Test
    fun theGroupOperandsFirstFilledValueIsTheSameFromEveryRow() =
        agreeFireRows(
            "FieldFilled($FINDING) And FirstFilledValue($HANDLING) == \"AAA\"",
            expected = listOf(
                "/Doc[1]/Handling[1]/Inspections[1]/Finding",
                "/Doc[1]/Handling[1]/Inspections[2]/Finding",
            ),
            msg = "FirstFilledValue over the operand answers AAA from both rows",
            build = ::twoDistinctNestedFindings,
        )

    /**
     * The mirror, which is what makes the pair two-sided: no row may fire on the value that is only ever the SECOND
     * filled cell. A pinned read would fire row 2 here.
     */
    @Test
    fun noRowSeesTheSecondNestedValueAsTheFirst() =
        agreeFireRows(
            "FieldFilled($FINDING) And FirstFilledValue($HANDLING) == \"BBB\"",
            expected = listOf(),
            msg = "BBB is never the operand's first filled value",
            build = ::twoDistinctNestedFindings,
        )

    /**
     * The control that keeps the direct field in the picture: filled, it precedes the nested rows
     * ([IF299]'s encounter-order law), so every row answers with it instead.
     */
    @Test
    fun theDirectFieldStillPrecedesTheNestedRowsFromADeepRuleRow() =
        agreeFireRows(
            "FieldFilled($FINDING) And FirstFilledValue($HANDLING) == \"DIRECT\"",
            expected = listOf(
                "/Doc[1]/Handling[1]/Inspections[1]/Finding",
                "/Doc[1]/Handling[1]/Inspections[2]/Finding",
            ),
            msg = "control: the direct PrimaryNote precedes both nested rows",
        ) { b ->
            b.group("/Doc", 1).field(REF, "D-1", 1, 1)
                .field(PRIMARY_NOTE, "DIRECT", 1, 1, 1)
                .group(INSPECTIONS, 1, 1, 1).field(FINDING, "AAA", 1, 1, 1, 1)
                .group(INSPECTIONS, 1, 1, 2).field(FINDING, "BBB", 1, 1, 2, 1)
        }
}
