package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — a **group-scope fill quantifier evaluated from a DEEP rule row**, the extent
 * [IG136]'s sentinel half names: `Document`'s `containedFieldCells` → `starRows` and `fillScope` → `instCells`
 * both call `starRows(field)` at the **default** bind depth, which resolves against the FIELD's parent rather
 * than the operand's.
 *
 * **Why no existing lock reaches this, including the one written an hour before it.** The cap that bind depth sets
 * only binds when the EVALUATING context is deep: `groupScopePrefix` takes
 * `rowReps.take(minOf(commonPrefixLen(operandParts, contextGroupParts), cap))`, so a rule authored on a shallow
 * root group makes `commonPrefixLen` 1 and the cap is never the binding term. IG136 states the recipe exactly — "a
 * repeatable level *between* operand and field, plus a non-starred reference to a field ON that level to anchor
 * iteration there" — and records that every current group-scope fixture is flat or guards on a non-repeatable
 * field, so the plan binds `Once` and nothing is pinned. `NestedGroupFillQuantifierDiffTest` has the nesting but
 * authors its rule on the root; this file has both.
 *
 * **The discriminator is a per-row FIRE SET, not a boolean**, because the two readings differ in *which rows* fire
 * rather than in whether any does. The rule is authored on the repeatable `Inspections` level and hangs its error
 * on a field there, so each row can report independently:
 *
 * | reading | `AllFieldsFilled(Handling)` | `NotAllFieldsFilled(Handling)` |
 * |---|---|---|
 * | operand-scoped (correct) | no row — `Inspections[1]/Finding` is empty | both rows |
 * | pinned to the rule's own row | row 2 only — its own Finding is filled | row 1 only |
 *
 * The two quantifiers are asserted as an **opposite-direction pair** on one document: a pinned read moves both fire
 * sets, and in opposite directions, which no single row could distinguish from an ordinary wrong verdict.
 */
class GroupScopeFromADeepRuleRowDiffTest {

    private companion object {
        const val REF = "/Return/Reference"
        const val HANDLING = "/Return/Handling"
        const val PRIMARY_NOTE = "/Return/Handling/PrimaryNote"
        const val INSPECTIONS = "/Return/Handling/Inspections"
        const val FINDING = "/Return/Handling/Inspections/Finding"
        const val CODE = "GSDR"
    }

    /**
     * `Handling` holds one direct String and a nested **repeatable** `Inspections` with one String. Exactly one
     * field per level is deliberate: the anchor that makes the plan iterate `Inspections` is the rule's own error
     * field reference, so no extra field is needed and none is added — an extra always-filled field would flatten
     * the fill tally and destroy the discriminator.
     */
    private val base = DocumentModelDraft.named("return-deep").locale("en_US")
        .rootGroup("Return") { g ->
            g.stringField("Reference") { }
            g.group("Handling") { h ->
                h.stringField("PrimaryNote") { }
                h.group("Inspections") { i -> i.repeatable(2).stringField("Finding") { } }
            }
        }.build().toJson()

    /**
     * The rule is authored ON the nested repeatable level and reports at its own `Finding`, so the fire SET is
     * per-row and a scope difference shows up as a different set of rows rather than as a flipped boolean.
     */
    private fun agreeFireRows(condition: String, expected: List<String>, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, INSPECTIONS, "R", "../Finding", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(kernelMessages.map { it.code() }.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape " +
                "(${kernelMessages.map { it.code() }.filter { it.startsWith("MVK_") }})"
        }
        val groovy = kernelMessages.filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US)
            .filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc)
            .filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /**
     * `PrimaryNote` filled, `Inspections[1]/Finding` EMPTY, `Inspections[2]/Finding` filled. So the operand's full
     * extent has an empty cell while row 2's own extent does not — which is what separates "the operand decides the
     * scope" from "the rule's row does".
     */
    private fun theFirstRowsFindingIsMissing(b: Both) {
        b.group("/Return", 1).field(REF, "RET-1", 1, 1)
            .field(PRIMARY_NOTE, "REPACKED", 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 1)
            .group(INSPECTIONS, 1, 1, 2).field(FINDING, "SCUFFED", 1, 1, 2, 1)
    }

    /**
     * **THE DISCRIMINATOR.** `AllFieldsFilled` over the whole operand is false from EVERY row, because row 1's
     * `Finding` is empty. A read pinned to the rule's own row fires on row 2, whose own cells are all filled.
     */
    @Test
    fun theGroupScopeSpansEveryRowFromADeepRuleRow() =
        agreeFireRows(
            "FieldFilled($FINDING) And AllFieldsFilled($HANDLING)",
            expected = listOf(),
            msg = "AllFieldsFilled over the operand's full extent is false from every row",
            build = ::theFirstRowsFindingIsMissing,
        )

    /**
     * The OPPOSITE-DIRECTION partner on the same document, and the reason the pair is stronger than either row:
     * a pinned read moves this fire set the other way (row 1 only) while moving the one above to row 2 only. An
     * ordinary wrong verdict cannot produce that pattern.
     *
     * The guard is `FieldFilled(Finding)`, so only row 2 can report at all — which is what makes the expected set
     * `[Inspections[2]/Finding]` rather than both rows, and what keeps the row honest about the guard's own effect.
     */
    @Test
    fun theOppositePolarityFiresFromTheRowThatCanReport() =
        agreeFireRows(
            "FieldFilled($FINDING) And NotAllFieldsFilled($HANDLING)",
            expected = listOf("/Return[1]/Handling[1]/Inspections[2]/Finding|OMISSION_ERROR"),
            msg = "NotAllFieldsFilled is true over the operand's extent; only row 2 passes the guard",
            build = ::theFirstRowsFindingIsMissing,
        )

    /**
     * The positive control: with every cell filled, `AllFieldsFilled` must fire from every row that can report.
     * Without it, the empty expectation above could mean the rule never evaluated from a deep row at all.
     */
    @Test
    fun theGroupScopeFiresFromADeepRuleRowWhenNothingIsMissing() =
        agreeFireRows(
            "FieldFilled($FINDING) And AllFieldsFilled($HANDLING)",
            expected = listOf(
                "/Return[1]/Handling[1]/Inspections[1]/Finding|VALUE_ERROR",
                "/Return[1]/Handling[1]/Inspections[2]/Finding|VALUE_ERROR",
            ),
            msg = "control: every cell below the operand is filled",
        ) { b ->
            b.group("/Return", 1).field(REF, "RET-1", 1, 1)
                .field(PRIMARY_NOTE, "REPACKED", 1, 1, 1)
                .group(INSPECTIONS, 1, 1, 1).field(FINDING, "TORN", 1, 1, 1, 1)
                .group(INSPECTIONS, 1, 1, 2).field(FINDING, "SCUFFED", 1, 1, 2, 1)
        }
}
