package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — a value aggregate over an **UNSTARRED group** operand, `Sum(/Order/Billing)`.
 *
 * **Why this exists.** The 2026-08-14 audit of the group-expansion class ([IF287], five confirmed instances)
 * ranked this third on the same structural argument that found the fifth: `starOperandVals` carries the group
 * branch and its file-mate `aggOperandVals` does not. The `else` arm resolves an operand through
 * `ctx.filled(pathOf(arg))`, which is `false` for **any** group path — a group holds no value of its own — so the
 * operand is dropped and the aggregate folds to its empty identity. The predicted symptom is therefore a silent
 * `Sum` of **0**, which is a legal total and fires nothing.
 *
 * **The unstarred spelling is the only legal one here**, and that is why the fixture's group is non-repeatable: a
 * *repeatable* group operand must carry its star (`MVK_NO_WILDCARD`), while a non-repeatable one is admitted
 * unstarred (KERNEL-FINDINGS [`kf173`], measured under `NumberOfDifferentValues`). So this arm is not an exotic
 * spelling of the starred one — it is the form a non-repeatable group has to use.
 *
 * **The admission guard is not ceremony.** Kernel admission of this operand slot under `Sum` specifically is not
 * something [`kf173`] measured; it measured the star gate under `Sum` and the unstarred admission under a sibling
 * carrier. If the kernel were to REJECT the shape, both engines would fall silent and every row below would pass
 * while measuring nothing — the exact vacuous-agreement trap. [assertAdmitted] fails on any `MVK_` diagnostic, so
 * a rejection is reported as a rejection rather than laundered into a green test.
 */
class UnstarredGroupSumOperandDiffTest {

    private companion object {
        const val REF = "/Order/Ref"
        const val BILLING = "/Order/Billing"
        const val FIRST = "/Order/Billing/First"
        const val SECOND = "/Order/Billing/Second"
        const val CODE = "SUMG"
    }

    /** A NON-repeatable group of two same-scale Numbers — homogeneous, so no kind gate can be the answer. */
    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ref") { }
            g.group("Billing") { b ->
                b.numberField("First") { it.fractionDigits(0, 0) }
                b.numberField("Second") { it.fractionDigits(0, 0) }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    /** `FieldFilled(Ref) And Sum(/Order/Billing) > [bar]` — the group scope, unstarred by the model's shape. */
    private fun groupScope(bar: Long): String = DslRenderer().render(
        m.field(REF).filled().and(Dsl.sum(m.flattenGroup(BILLING)).gt(bar)),
    )

    /** The control: the same two fields listed explicitly, so a disagreement cannot be about the fixture. */
    private fun explicitFields(bar: Long): String = DslRenderer().render(
        m.field(REF).filled().and(Dsl.sum(m.field(FIRST), m.field(SECOND)).gt(bar)),
    )

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Ref", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, msg)
        val groovy = kernelMessages.any { it.code() == CODE }

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /** A rejected rule makes every row below vacuous, so the rejection must be louder than the comparison. */
    private fun assertAdmitted(codes: List<String>, msg: String) =
        assertTrue(codes.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape (${codes.filter { it.startsWith("MVK_") }}), so the rows " +
                "below would compare two silences; this is a measurement about admission, not about the runtime"
        }

    /** 3 + 4 = 7 clears the bar only if the group's fields are summed at all; an empty fold gives 0. */
    private fun threeAndFour(b: Both) {
        b.group("/Order", 1).field(REF, "R-1", 1, 1)
            .field(FIRST, "3", 1, 1, 1).field(SECOND, "4", 1, 1, 1)
    }

    @Test
    fun theGroupScopeSumsItsContainedFields() =
        agreeFires(groupScope(5), expected = true, msg = "Sum(/Order/Billing) = 7 > 5", build = ::threeAndFour)

    /** The same document and bar through the explicit field list — the fixture control. */
    @Test
    fun theExplicitFieldListSumsTheSameWay() =
        agreeFires(explicitFields(5), expected = true, msg = "Sum(First, Second) = 7 > 5", build = ::threeAndFour)

    /** The negative control: 7 does not clear 9, so a firing is about the total rather than about evaluating. */
    @Test
    fun theGroupScopeDoesNotOversum() =
        agreeFires(groupScope(9), expected = false, msg = "Sum(/Order/Billing) = 7 does not exceed 9", build = ::threeAndFour)

    /**
     * The discriminator against a *partial* expansion: only the SECOND field is filled. A sum that reads one
     * declared field, or none, misses it; the bar is set so any correct total fires and any partial one does not.
     */
    @Test
    fun theGroupScopeReachesAFieldThatIsFilledAlone() =
        agreeFires(groupScope(5), expected = true, msg = "only Second = 6 is filled; 6 > 5") { b ->
            b.group("/Order", 1).field(REF, "R-1", 1, 1).field(SECOND, "6", 1, 1, 1)
        }
}
