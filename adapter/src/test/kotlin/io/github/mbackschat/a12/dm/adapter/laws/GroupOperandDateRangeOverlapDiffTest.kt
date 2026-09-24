package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — **a GROUP operand in `AtLeastOneDateRangeOverlaps`' `In` slot**, the shape that
 * reopened a question [IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287) had closed.
 *
 * **The premise this file corrects.** IF287 deliberately did NOT push group expansion into the shared
 * `filteredStarCells`, on the stated grounds that "its other caller is the date-range overlap operator, whose
 * behaviour over a group operand is **unmeasured**" — declining to widen a shared path past its measured basis,
 * which is the right instinct. The 2026-08-14 admission sweep then measured it, and the family turns out to
 * DISAGREE WITH ITSELF: `DateRangesOverlap(<group>)` is refused `MVK_NO_GROUPS_ALLOWED` in both repetition shapes,
 * while the plural `AtLeastOneDateRangeOverlaps(<scalar> In <group>)` is **ACCEPTED**
 * ([KF187](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf187)). So half the reason for withholding the
 * expansion was correct and the other half was not, and the shape is authorable after all.
 *
 * **Both arms of the dispatch are blind, for two different reasons, and they need separate rows.**
 * `Interpreter.overlapCells` dispatches `is Expr.Star -> filteredStarCells(arg)` versus
 * `else -> listOfNotNull(evalExpr(arg) as? Val.Range)`. For a STARRED group the first arm reads `star` at the
 * group path, where no field is declared — zero cells. For an UNSTARRED group the second arm asks `evalExpr` for a
 * `Val.Range` at a path that holds no value at all — never a range. One function, two arms, the same blind spot by
 * different mechanisms: this class's signature.
 *
 * **The discriminator is deliberately reachable only by a correct expansion in TWO ways at once.** The scalar
 * window overlaps exactly one cell below the group — `Periods[2]/Grace` — so it is missed by a row-1 pin AND by an
 * expansion that reaches only the group's first declared field. The per-field controls are what make that precise:
 * the same scalar against `Grace` alone fires, and against `Window` alone does not.
 */
class GroupOperandDateRangeOverlapDiffTest {

    private companion object {
        const val REF = "/Policy/Reference"
        const val CLAIM_WINDOW = "/Policy/ClaimWindow"
        const val COVER = "/Policy/Cover"
        const val POLICY_WINDOW = "/Policy/Cover/PolicyWindow"
        const val EXTENSION_WINDOW = "/Policy/Cover/ExtensionWindow"
        const val PERIODS = "/Policy/Periods"
        const val WINDOW = "/Policy/Periods/Window"
        const val GRACE = "/Policy/Periods/Grace"
        const val CODE = "GDRO"

        /** The scalar the group's cells are tested against — it overlaps `Periods[2]/Grace` and nothing else. */
        const val JUNE_MIDDLE = "10.06.2026-20.06.2026"
    }

    /**
     * A policy model with a DATERANGE scalar at the ROOT — outside any group — which is what makes the unstarred
     * variant probeable at all: the operator's first slot refuses a group and any wildcard, so without a scalar
     * outside the group under test there is no legal left-hand operand that is not already inside it (and
     * `checkParamsReferencedOnlyOnce` would then fire). The sweep could not measure that arm for exactly this
     * reason. Every group is homogeneous in kind.
     */
    private val base = DocumentModelDraft.named("policy-periods").locale("en_US")
        .rootGroup("Policy") { g ->
            g.stringField("Reference") { }
            g.dateRangeField("ClaimWindow") { it.format("dd.MM.yyyy").separator("-") }
            g.group("Cover") { c ->
                c.dateRangeField("PolicyWindow") { it.format("dd.MM.yyyy").separator("-") }
                    .dateRangeField("ExtensionWindow") { it.format("dd.MM.yyyy").separator("-") }
            }
            g.group("Periods") { p ->
                p.repeatable(2)
                    .dateRangeField("Window") { it.format("dd.MM.yyyy").separator("-") }
                    .dateRangeField("Grace") { it.format("dd.MM.yyyy").separator("-") }
            }
        }.build().toJson()

    /**
     * The full `errorPath|type` signature of every message carrying [CODE], sorted — NOT a boolean.
     *
     * A boolean makes a message of the wrong TYPE read as agreement, and that is not hypothetical: the ninth
     * instance of this defect class shipped green under a boolean lock written in the same commit as its own fix,
     * and the tenth and thirteenth were both found by widening a projection rather than by adding a case. The
     * kernel's own verdict is still asserted as a boolean (`expected`), because that is what each row's document
     * is designed to produce; what is compared ACROSS engines is the signature.
     */
    private fun signature(messages: List<RuntimeLaws.Msg>): List<String> =
        messages.filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()

    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Policy", "R", "../Reference", CODE,
            "FieldFilled($REF) And $condition")
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertTrue(kernelMessages.map { it.code() }.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED `$condition` " +
                "(${kernelMessages.map { it.code() }.filter { it.startsWith("MVK_") }}); that is a measurement " +
                "about admission and the row below would compare two silences"
        }
        val groovy = signature(kernelMessages)

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = signature(
            RuntimeLaws.kernelJavaEngine(kModel).messagesModern(staticDoc, null, java.util.Locale.US))

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(expected, groovy.isNotEmpty(), "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /**
     * The scalar sits in mid-June. Below `Periods`, only `Periods[2]/Grace` reaches it — so a correct expansion
     * must cross to the second ROW *and* to the second FIELD, and either failure alone misses the overlap.
     */
    private fun onlyTheSecondRowsGraceOverlaps(b: Both) {
        b.group("/Policy", 1).field(REF, "P-1", 1, 1)
            .field(CLAIM_WINDOW, JUNE_MIDDLE, 1, 1)
            .group(COVER, 1, 1)
            .field(POLICY_WINDOW, JUNE_MIDDLE, 1, 1, 1)
            .field(EXTENSION_WINDOW, "01.12.2026-31.12.2026", 1, 1, 1)
            .group(PERIODS, 1, 1)
            .field(WINDOW, "01.01.2026-31.01.2026", 1, 1, 1)
            .field(GRACE, "01.02.2026-28.02.2026", 1, 1, 1)
            .group(PERIODS, 1, 2)
            .field(WINDOW, "01.03.2026-31.03.2026", 1, 2, 1)
            .field(GRACE, "15.06.2026-25.06.2026", 1, 2, 1)
    }

    /** **THE STARRED-GROUP DISCRIMINATOR** — the `filteredStarCells` arm. */
    @Test
    fun theStarredGroupOperandReachesEveryRowAndField() =
        agreeFires("AtLeastOneDateRangeOverlaps($POLICY_WINDOW In $PERIODS*)", expected = true,
            msg = "starred group: only Periods[2]/Grace overlaps mid-June",
            build = ::onlyTheSecondRowsGraceOverlaps)

    /** The per-field control that fires — so a `false` above could not be blamed on the fixture. */
    @Test
    fun theExplicitGraceListReachesTheSameOverlap() =
        agreeFires("AtLeastOneDateRangeOverlaps($POLICY_WINDOW In $PERIODS*/Grace)", expected = true,
            msg = "control: the explicit Grace list reaches Periods[2]/Grace",
            build = ::onlyTheSecondRowsGraceOverlaps)

    /**
     * The per-field control that does NOT fire. Read with the row above, it is what shows the group operand had to
     * reach the group's SECOND declared field rather than merely its first.
     */
    @Test
    fun theExplicitWindowListFindsNoOverlap() =
        agreeFires("AtLeastOneDateRangeOverlaps($POLICY_WINDOW In $PERIODS*/Window)", expected = false,
            msg = "control: no Periods Window overlaps mid-June",
            build = ::onlyTheSecondRowsGraceOverlaps)

    /**
     * **THE UNSTARRED-GROUP variant**, which the admission sweep could not reach and which exercises the OTHER arm
     * of `overlapCells` — the one that asks `evalExpr` for a range at a group path. The root scalar is the
     * left-hand operand so the non-repeatable `Cover` group can be the right-hand one.
     */
    @Test
    fun theUnstarredGroupOperandContributesItsRanges() =
        agreeFires("AtLeastOneDateRangeOverlaps($CLAIM_WINDOW In $COVER)", expected = true,
            msg = "unstarred group: Cover/PolicyWindow overlaps the claim window",
            build = ::onlyTheSecondRowsGraceOverlaps)

    /** Its explicit control, so a divergence isolates the group spelling. */
    @Test
    fun theExplicitCoverFieldsContributeTheSameRanges() =
        agreeFires("AtLeastOneDateRangeOverlaps($CLAIM_WINDOW In $POLICY_WINDOW, $EXTENSION_WINDOW)",
            expected = true, msg = "control: the explicit Cover fields overlap the claim window",
            build = ::onlyTheSecondRowsGraceOverlaps)

    /** The negative on the unstarred arm: a scalar outside every Cover range must not overlap. */
    @Test
    fun theUnstarredGroupFindsNoOverlapForADisjointScalar() =
        agreeFires("AtLeastOneDateRangeOverlaps($CLAIM_WINDOW In $COVER)", expected = false,
            msg = "unstarred group: the claim window is disjoint from both Cover ranges") { b ->
            b.group("/Policy", 1).field(REF, "P-1", 1, 1)
                .field(CLAIM_WINDOW, "01.09.2026-30.09.2026", 1, 1)
                .group(COVER, 1, 1)
                .field(POLICY_WINDOW, JUNE_MIDDLE, 1, 1, 1)
                .field(EXTENSION_WINDOW, "01.12.2026-31.12.2026", 1, 1, 1)
                .group(PERIODS, 1, 1)
                .group(PERIODS, 1, 2)
        }
}
