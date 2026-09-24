package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check — the **THIRD member of the uncapped-`common` family**, [IG136] item (i).
 *
 * **Why this had the highest prior of anything left open.** `Document` carries three implementations of "which
 * repetition levels bind to this row and which iterate", and the other two were BOTH confirmed defects in the same
 * session: `fillScope` ([IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)'s sixteenth
 * instance) and `containedFieldCells` (its nineteenth, which a cold review found because the sixteenth's fix was
 * not swept across its siblings). `starScanCells` is the third, and its `declaredRange` branch is worse-looking
 * than either: it computes `commonPrefixLen(fieldParts, partsOf(group))` **and ignores `bindDepth` entirely**,
 * although the parameter is right there and its own non-declared branch uses it.
 *
 * **A larger `common` binds MORE levels to the evaluating row**, so an over-large one under-reports: cells the
 * operand should reach are never visited. With a MULTI-star operand the correct binding is the first starred
 * level's parent (`bindDepth`, [IF94]); with the rule authored ON an inner starred level, the shared prefix with
 * the evaluating group is deeper than that and pins levels the stars say must iterate.
 *
 * **The reachable caller is the COMPUTE path**, `scanFilled`'s declared-tail scan — which is why this is a
 * computation rather than a rule. The target is a sibling field of the operand rather than the operand itself,
 * because a computation whose target sits inside its own operand draws `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD`
 * (measured while building the nineteenth instance's lock).
 *
 * | reading | the four `Tally` cells |
 * |---|---|
 * | multi-star (correct) | all CLEARED — one `Detail` below the operand is empty |
 * | pinned to the evaluating row | three compute, only `A[1]/B[2]` clears |
 */
class MultiStarDeclaredScanScopeDiffTest {

    private val REF = "/Doc/Reference"
    private val DETAIL = "/Doc/Outer/Inner/Detail"
    private val TALLY = "/Doc/Outer/Inner/Tally"
    private val INNER = "/Doc/Outer/Inner"

    /** The operand: BOTH levels starred, so both must iterate whatever the computation's own row is. */
    private val DETAIL_MULTI_STAR = "/Doc/Outer*/Inner*/Detail"

    private val base = DocumentModelDraft.named("multistar-scan").locale("en_US")
        .rootGroup("Doc", Consumer { g: GroupBuilder ->
            g.stringField("Reference", Consumer { })
                .group("Outer", Consumer { o ->
                    o.repeatable(2).group("Inner", Consumer { i ->
                        i.repeatable(2)
                            .stringField("Detail", Consumer { })
                            .numberField("Tally", Consumer { })
                    })
                })
        }).build().toJson()

    private fun agree(precondition: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withTieredComputation(base, INNER, "RowComp", "../Tally", null,
            listOf(RuntimeLaws.CompAlt(precondition, "7")))
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }

        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.compute(engine.instance(kDoc2))
            .associate { "${it.path()}@${it.pointer()}" to kSig(it) }

        val interp = DmInterpreter(def).compute(b.iDoc)
            .associate { "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it) }

        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs INTERPRETER")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun cell(o: Int, i: Int) = "$TALLY@/Doc[1]/Outer[$o]/Inner[$i]/Tally"

    /** Exactly one `Detail` below the operand is empty — `Outer[1]/Inner[2]`; every `Tally` is seeded. */
    private fun oneMissingDetail(b: Both) {
        b.group("/Doc", 1).field(REF, "D-1", 1, 1)
        for (o in 1..2) {
            for (i in 1..2) {
                b.group(INNER, 1, o, i).field(TALLY, "1", 1, o, i, 1)
                if (!(o == 1 && i == 2)) b.field(DETAIL, "X", 1, o, i, 1)
            }
        }
    }

    /**
     * **THE DISCRIMINATOR.** `AllFieldsFilled` over a doubly-starred operand is false from EVERY row, because one
     * cell below it is empty — so all four targets clear. A scan that binds the evaluating row sees only its own
     * `Detail` and computes the three rows whose own cell is filled.
     */
    @Test
    fun theDoublyStarredScanSpansEveryRowFromAnInnerRow() =
        agree("AllFieldsFilled($DETAIL_MULTI_STAR)",
            "one Detail below the doubly-starred operand is empty, so no row computes",
            mapOf(
                cell(1, 1) to "CLEARED", cell(1, 2) to "CLEARED",
                cell(2, 1) to "CLEARED", cell(2, 2) to "CLEARED",
            ),
            build = ::oneMissingDetail)

    /**
     * The positive control: with every `Detail` filled, all four compute. Without it the all-CLEARED expectation
     * above would also be satisfied by a precondition that never holds.
     */
    @Test
    fun theDoublyStarredScanComputesWhenNothingIsMissing() =
        agree("AllFieldsFilled($DETAIL_MULTI_STAR)",
            "control: every Detail below the operand is filled",
            mapOf(
                cell(1, 1) to "VALUE|7", cell(1, 2) to "VALUE|7",
                cell(2, 1) to "VALUE|7", cell(2, 2) to "VALUE|7",
            )) { b ->
            b.group("/Doc", 1).field(REF, "D-1", 1, 1)
            for (o in 1..2) {
                for (i in 1..2) {
                    b.group(INNER, 1, o, i).field(TALLY, "1", 1, o, i, 1).field(DETAIL, "X", 1, o, i, 1)
                }
            }
        }
}
