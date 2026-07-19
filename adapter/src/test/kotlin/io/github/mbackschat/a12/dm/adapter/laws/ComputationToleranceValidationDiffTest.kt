package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — a computation alternative's `toleranceRangeOp` (`DiffersWithToleranceRange{1,2,5,10}`): the
 * kernel's implicit computation-validation compares the stored value against the operation with the matching
 * tolerance band (fires iff `|stored − op| > N`) instead of strict `!=` (IF148, graduated from IG96). So a stored
 * value WITHIN ±N — including exactly at the boundary — does not fire, and only a value more than N away fires.
 * Was a strict-`!=` over-fire in the interpreter. Own domain (an order with a tolerated fee). Tri-check:
 * groovy-dynamic == static-Java == interpreter (via {@code firedCodes}).
 */
class ComputationToleranceValidationDiffTest {

    private val NAME = "FeeCheck"

    /** Order{ Base, Fee } + FeeCheck: Fee := [Base] with the given `toleranceRangeOp` (a guarded tier when [guard]). */
    private fun model(band: Int, guard: Boolean): String {
        val base = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", Consumer { g: GroupBuilder ->
                g.numberField("Base", Consumer { })
                g.numberField("Fee", Consumer { })
            }).build().toJson()
        val tol = "DiffersWithToleranceRange$band"
        return RuntimeLaws.withTieredComputation(base, "/Order", NAME, "../Fee",
            if (guard) "FieldFilled(Base)" else null,
            listOf(RuntimeLaws.CompAlt(null, "[Base]", tol)))
    }

    /** Assert groovy-dynamic == static-Java == interpreter on whether the implicit validation fires. */
    private fun agree(band: Int, base: Int, fee: Int, expectFire: Boolean, guard: Boolean = false) {
        val kModel = model(band, guard)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
            .field("/Order/Base", base.toString(), 1, 1).field("/Order/Fee", fee.toString(), 1, 1)
        val iDoc = def.newDocument().group("/Order", 1)
            .field("/Order/Base", base.toString(), 1, 1).field("/Order/Fee", fee.toString(), 1, 1)

        val groovy = RuntimeLaws.firedCodes(kModel, kDoc).contains(NAME)
        val java = RuntimeLaws.kernelJavaEngine(kModel).firedCodesSorted(
            RuntimeLaws.kernelJavaEngine(kModel).instance(kDoc)).contains(NAME)
        val interp = DmInterpreter(def).validate(iDoc).messages.any { it.code == NAME }

        val msg = "band=$band |$fee-$base|=${kotlin.math.abs(fee - base)} guard=$guard"
        assertEquals(expectFire, groovy, "$msg — kernel groovy-dynamic")
        assertEquals(groovy, java, "$msg — groovy vs static-Java")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    @Test fun equalNeverFires() = listOf(1, 2, 5, 10).forEach { agree(it, 100, 100, expectFire = false) }

    @Test fun insideTheBandDoesNotFire() {
        agree(2, 100, 101, expectFire = false)   // diff 1 < 2
        agree(5, 100, 103, expectFire = false)   // diff 3 < 5
        agree(10, 100, 95, expectFire = false)   // diff 5 < 10 (below)
    }

    @Test fun atTheBoundaryDoesNotFire() {
        agree(1, 100, 101, expectFire = false)   // diff 1 == 1
        agree(2, 100, 102, expectFire = false)   // diff 2 == 2
        agree(5, 100, 105, expectFire = false)   // diff 5 == 5
        agree(10, 100, 90, expectFire = false)   // diff 10 == 10 (below)
    }

    @Test fun justOutsideTheBandFires() {
        agree(1, 100, 102, expectFire = true)    // diff 2 > 1
        agree(2, 100, 103, expectFire = true)    // diff 3 > 2
        agree(5, 100, 106, expectFire = true)    // diff 6 > 5
        agree(10, 100, 111, expectFire = true)   // diff 11 > 10
    }

    @Test fun aGuardedToleranceTierBehavesTheSame() {
        agree(2, 100, 101, expectFire = false, guard = true)   // inside → not fire
        agree(2, 100, 103, expectFire = true, guard = true)    // outside → fire
    }
}
