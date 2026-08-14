package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check — a fill quantifier over a **starred GROUP** evaluated on the **COMPUTE** path.
 *
 * **Why this exists, and why it is a separate file from the validation twin.** The 2026-08-14 audit of the
 * group-expansion class ([IF287], six confirmed instances) found that the two evaluation modes take different
 * code: validation's `scanFilled` dispatches on `fillScope(isGroup = …)` and expands a group, while the compute
 * arm reads `starScanCells`, which does not. A **mode split** is the nastiest member of this class, because the
 * operator is demonstrably correct wherever the estate happens to test it and wrong on the path nobody paired.
 *
 * The discriminator is `NoFieldFilled` rather than `AllFieldsFilled`, deliberately: over an *unexpanded* group
 * star the read yields no cells at all, and a quantifier over zero cells answers vacuously — `NoFieldFilled`
 * becomes TRUE where the correct answer over filled cells is FALSE. `AllFieldsFilled` would also answer
 * vacuously TRUE, which is the *same* verdict a correct implementation gives on a fully-filled document, so it
 * cannot separate the two readings. Every cell in the fixture is filled, so the correct precondition is FALSE and
 * the computation must NOT run; an unexpanded read runs it and writes a value.
 *
 * Shaped after [BareGroupComputePoisonDiffTest], whose `NoFieldFilled(Details)` covers the *bare, unstarred*
 * group on this same path — the starred cell is what was missing.
 */
class GroupStarFillComputeModeDiffTest {

    private companion object {
        const val FLAG = "/Invoice/Flag"
        const val CHARGES = "/Invoice/Charges"
        const val A = "/Invoice/Charges/A"
        const val B = "/Invoice/Charges/B"
    }

    /** `Flag` is the computed target; `Charges` repeats, so its operand must carry the star. */
    private fun model(precondition: String): String {
        val base = DocumentModelDraft.named("invoice").locale("en_US")
            .rootGroup("Invoice", Consumer { g ->
                g.numberField("Flag", Consumer { it.fractionDigits(0, 0) })
                g.group("Charges", Consumer { c ->
                    c.repeatable(10).stringField("A", Consumer { }).stringField("B", Consumer { })
                })
            }).build().toJson()
        return RuntimeLaws.withTieredComputation(
            base, "/Invoice", "FlagComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt(precondition, "1")),
        )
    }

    /** Two rows, every cell filled — so any correct fill quantifier sees four filled cells. */
    private fun fill(set: (String, String, IntArray) -> Unit) {
        set(FLAG, "9", intArrayOf(1, 1))          // seed the target so a kernel CLEAR is reported rather than silence
        set(A, "a1", intArrayOf(1, 1, 1)); set(B, "b1", intArrayOf(1, 1, 1))
        set(A, "a2", intArrayOf(1, 2, 1)); set(B, "b2", intArrayOf(1, 2, 1))
    }

    private fun agree(precondition: String, expected: String, msg: String) {
        val kModel = model(precondition)
        val def = ModelLoader.load(kModel)

        fun kernelDoc(): RuntimeLaws.Doc {
            val d = RuntimeLaws.doc(); d.group("/Invoice", 1)
            d.group(CHARGES, 1, 1); d.group(CHARGES, 1, 2)
            fill { p, v, r -> d.field(p, v, *r) }
            return d
        }

        val groovy = RuntimeLaws.compute(kModel, kernelDoc()).first { it.path() == FLAG }
            .let { if (it.cleared()) "CLEARED" else if (it.errored()) "ERRORED" else "VALUE|${it.value().orElse("")}" }

        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val java = engine.computeOutcomesSorted(engine.instance(kernelDoc())).first { it.startsWith("$FLAG|") }
            .substringAfter('|').let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" }

        val iDoc = def.newDocument(); iDoc.group("/Invoice", 1)
        iDoc.group(CHARGES, 1, 1); iDoc.group(CHARGES, 1, 2)
        fill { p, v, r -> iDoc.field(p, v, *r) }
        val interp = DmInterpreter(def).compute(iDoc).first { it.path == FLAG }.let {
            when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
        }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, java, "$msg — groovy vs static-Java (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs INTERPRETER")
    }

    /**
     * **The discriminator.** Four filled cells across two rows, so `NoFieldFilled` is FALSE and no alternative
     * applies — the seeded target is cleared. A compute arm that cannot expand the group star reads zero cells,
     * answers vacuously TRUE, and writes `1`.
     */
    @Test
    fun theStarredGroupIsExpandedOnTheComputePathToo() =
        agree("NoFieldFilled($CHARGES*)", "CLEARED", "every Charges cell is filled → NoFieldFilled is false")

    /**
     * The positive control on the same shape: with the polarity flipped the precondition IS satisfied, so the
     * computation runs and writes `1`. Without it a `CLEARED` above could mean "the precondition never
     * evaluated" rather than "it evaluated to false".
     */
    @Test
    fun theSameStarredGroupSatisfiesItsPositivePrecondition() =
        agree("AtLeastOneFieldFilled($CHARGES*)", "VALUE|1", "some Charges cell is filled → the tier applies")
}
