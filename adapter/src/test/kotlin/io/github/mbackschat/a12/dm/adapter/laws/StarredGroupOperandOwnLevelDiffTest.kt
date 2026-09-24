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
 * Differential + tri-check — a **STARRED group operand evaluated from a rule authored ON its own repeatable
 * level**, [IG136] item (j), and the shape where the two channels of one operand were found to disagree.
 *
 * **The disagreement, stated precisely.** A starred group operand reaches the runtime by two different routes:
 *
 * - the fill quantifiers go through `fillScopeInfo` → `EvalContext.fillScope(scopePath, isGroup = true)`, a seam
 *   that takes **no `bindDepth` at all** and therefore caps its bound prefix at the operand's PATH depth — which,
 *   for `G*`, includes `G`'s own level;
 * - `NumberOfFilledFields` and the compute scan go through `expandGroupStars`, which binds each expanded field at
 *   `expansionBindDepth`, and for `G*` that is `G`'s PARENT — so `G`'s own level iterates.
 *
 * From a rule authored anywhere above `G` the difference is invisible, because `commonPrefixLen` is the binding
 * term and the cap never applies. From a rule authored ON `G` it is not. Two channels of one operand disagreeing
 * about its extent is this class's own cheapest tell — it is how the fourth instance of
 * [IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287) was found — and `Document.rowContexts`'
 * own documentation states the principle for this case in the opposite direction: "an aggregate/quantifier over
 * groupPath spans all rows under groupPath's PARENT … scoping to the full rowReps would wrongly return only this
 * one row".
 *
 * **The fixture accepts the operand's own error field.** `Marker` is inside `G` and therefore inside the
 * expansion, so it is filled in EVERY row: it then contributes identically under both readings, and the only
 * thing that differs is WHICH rows are in scope. That is the same device the compute-path sibling needed, and it
 * is forced — the rule must report per row for a fire set to exist at all, and a rule on `G` can only report
 * inside `G`.
 */
class StarredGroupOperandOwnLevelDiffTest {

    private companion object {
        const val REF = "/Doc/Reference"
        const val LOTS = "/Doc/Lots"
        const val LOTS_STAR = "/Doc/Lots*"
        const val DETAIL = "/Doc/Lots/Detail"
        const val MARKER = "/Doc/Lots/Marker"
        const val CODE = "SGOL"
    }

    private val base = DocumentModelDraft.named("own-level").locale("en_US")
        .rootGroup("Doc") { g ->
            g.stringField("Reference") { }
            g.group("Lots") { l ->
                l.repeatable(2).stringField("Detail") { }.stringField("Marker") { }
            }
        }.build().toJson()

    /** The rule sits ON `Lots` and reports at its own `Marker`, so a scope difference is a different FIRE SET. */
    private fun agreeFireRows(condition: String, expected: List<String>, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, LOTS, "R", "../Marker", CODE, condition)
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

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /** `Detail` is missing in row 1 only; every `Marker` is filled so the error field never decides the scope. */
    private fun theFirstRowsDetailIsMissing(b: Both) {
        b.group("/Doc", 1).field(REF, "D-1", 1, 1)
            .group(LOTS, 1, 1).field(MARKER, "M1", 1, 1, 1)
            .group(LOTS, 1, 2).field(DETAIL, "X", 1, 2, 1).field(MARKER, "M2", 1, 2, 1)
    }

    /**
     * **CHANNEL A — the fill quantifier**, which reaches `fillScope`. `AllFieldsFilled(Lots*)` over the operand's
     * full extent is false from every row, because row 1's `Detail` is empty. A read that binds `Lots`' own level
     * answers row 2 with its own complete cells and fires there.
     */
    @Test
    fun theFillQuantifierSpansEveryRowOfItsOwnStarredLevel() =
        agreeFireRows("FieldFilled($MARKER) And AllFieldsFilled($LOTS_STAR)",
            expected = listOf(),
            msg = "channel A: AllFieldsFilled over Lots* is false from every row",
            build = ::theFirstRowsDetailIsMissing)

    /**
     * **CHANNEL B — the same operand through `expandGroupStars`.** Three cells below `Lots*` are filled
     * (`Marker` twice, `Detail` once), so `> 1` holds from every row. A read that bound `Lots`' own level would
     * see one `Marker` from row 1 and answer false there — so the two channels' fire sets are what expose a
     * disagreement, and they must be equal.
     */
    @Test
    fun theCountChannelSpansTheSameRows() =
        agreeFireRows("FieldFilled($MARKER) And NumberOfFilledFields($LOTS_STAR) > 1",
            expected = listOf(
                "/Doc[1]/Lots[1]/Marker|VALUE_ERROR",
                "/Doc[1]/Lots[2]/Marker|VALUE_ERROR",
            ),
            msg = "channel B: three cells below Lots* are filled, from every row",
            build = ::theFirstRowsDetailIsMissing)

    /**
     * The positive control for channel A: with every cell filled it must fire from every row. Without it the
     * empty expectation above would also be satisfied by a quantifier that never fires over this operand.
     */
    @Test
    fun theFillQuantifierFiresWhenNothingIsMissing() =
        agreeFireRows("FieldFilled($MARKER) And AllFieldsFilled($LOTS_STAR)",
            expected = listOf(
                "/Doc[1]/Lots[1]/Marker|VALUE_ERROR",
                "/Doc[1]/Lots[2]/Marker|VALUE_ERROR",
            ),
            msg = "control: every cell below Lots* is filled") { b ->
            b.group("/Doc", 1).field(REF, "D-1", 1, 1)
                .group(LOTS, 1, 1).field(DETAIL, "X", 1, 1, 1).field(MARKER, "M1", 1, 1, 1)
                .group(LOTS, 1, 2).field(DETAIL, "Y", 1, 2, 1).field(MARKER, "M2", 1, 2, 1)
        }
}
