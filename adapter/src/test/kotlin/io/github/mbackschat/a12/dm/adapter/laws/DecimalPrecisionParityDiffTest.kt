package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the **negative power** operator
 * (IF128-adjacent; part of IF153). The kernel computes `a ^ -n` as `(1/a)^n`
 * (`VkBigDecimal.power` → `getKehrwert(this).power(|n|)`, each step at `MathContext(50)` HALF_UP), NOT as
 * `1/(a^n)`. The two forms agree everywhere the 50-significant-digit rounding is invisible, but diverge for a
 * **large-magnitude** result whose difference survives the kernel's scale-19 NUMBER-comparison pre-round
 * (`VkBigDecimal.DEFAULT_SCALE`). `0.3 ^ -64` has 34 integer digits, so `(1/0.3)^64 = …4484006` vs the old
 * actual's `1/(0.3^64) = …4484025` differ at the 17th fractional digit — well inside scale-19.
 *
 * The discriminating threshold `…4484015` sits strictly between the two forms, so the OLD interpreter (which
 * computed `1/(a^n)`) fired where the kernel is silent. Locking the reciprocal-first form here.
 */
class DecimalPrecisionParityDiffTest {

    private companion object {
        const val RATE = "/Policy/Rate"
        // the kernel value of 0.3 ^ -64 is …4484006 ; the old 1/(0.3^64) form was …4484025 ; this splits them
        const val SPLIT = "2912324058756262786433368781182555.3552912554484015"
        const val BELOW = "1000000"                                   // far under the ~2.9e33 result → both fire
        const val ABOVE = "9999999999999999999999999999999999999999"  // 1e40, far over → both silent
    }

    private val base = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy") { g -> g.numberField("Rate") { it.fractionDigits(0, 2) } }
        .build().toJson()

    private val m = BoundModel.ofJson(base)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private fun ratePow64Gt(threshold: String): String =
        render(m.num(RATE).asNumber().toThePowerOf(Dsl.number("-64")).gt(threshold))

    /** Tri-check whether the `Rate ^ -64 > [threshold]` rule fires with `Rate = 0.30`. */
    private fun agree(threshold: String, msg: String) {
        val cond = ratePow64Gt(threshold)
        val kModel = RuntimeLaws.withRule(base, "/Policy", "R", "../Rate", "NEGPOW", cond)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Policy", 1).field(RATE, "0.30", 1, 1)
        val groovy = RuntimeLaws.messages(kModel, kDoc).any { it.code() == "NEGPOW" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains("NEGPOW")
        val iDoc = def.newDocument().group("/Policy", 1).field(RATE, "0.30", 1, 1)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "NEGPOW" }
        assertEquals(groovy, javaStatic, "$msg — groovy-dynamic vs java-static [$cond]")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter [$cond]")
    }

    /** The discriminator: the kernel `(1/0.3)^64` is BELOW the split, so the rule is silent; the old actual's
     *  `1/(0.3^64)` was above it and wrongly fired. */
    @Test fun negativePowerReciprocalFormAtTheSplit() = agree(SPLIT, "0.3^-64 vs the form-splitting threshold")

    @Test fun negativePowerFiresWellBelow() = agree(BELOW, "0.3^-64 ≫ 1e6 → both fire")

    @Test fun negativePowerSilentWellAbove() = agree(ABOVE, "0.3^-64 ≪ 1e40 → both silent")
}
