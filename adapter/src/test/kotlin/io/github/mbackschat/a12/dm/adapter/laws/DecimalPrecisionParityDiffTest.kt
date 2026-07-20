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
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the kernel's complete
 * `MathContext(50)` arithmetic contract. The kernel computes `a ^ -n` as `(1/a)^n`
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
        const val EXPONENT = "/Policy/Exponent"
        // the kernel value of 0.3 ^ -64 is …4484006 ; the old 1/(0.3^64) form was …4484025 ; this splits them
        const val SPLIT = "2912324058756262786433368781182555.3552912554484015"
        const val BELOW = "1000000"                                   // far under the ~2.9e33 result → both fire
        const val ABOVE = "9999999999999999999999999999999999999999"  // 1e40, far over → both silent
    }

    private val base = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy") { g ->
            g.numberField("Rate") { it.fractionDigits(0, 14) }
            g.numberField("Exponent") { it.fractionDigits(0) }
        }
        .build().toJson()

    private val m = BoundModel.ofJson(base)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Tri-check a legal condition against both real kernel strategies and the interpreter. */
    private fun agree(condition: Condition, rate: String, msg: String, expected: Boolean? = null) {
        agree(condition, mapOf(RATE to rate), msg, expected)
    }

    private fun agree(condition: Condition, values: Map<String, String>, msg: String, expected: Boolean? = null) {
        val cond = render(condition)
        val kModel = RuntimeLaws.withRule(base, "/Policy", "R", "../Rate", "NEGPOW", cond)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Policy", 1)
        values.forEach { (path, value) -> kDoc.field(path, value, 1, 1) }
        val groovy = RuntimeLaws.messages(kModel, kDoc).any { it.code() == "NEGPOW" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains("NEGPOW")
        val iDoc = def.newDocument().group("/Policy", 1)
        values.forEach { (path, value) -> iDoc.field(path, value, 1, 1) }
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "NEGPOW" }
        expected?.let { assertEquals(it, groovy, "$msg — expected kernel outcome [$cond]") }
        assertEquals(groovy, javaStatic, "$msg — groovy-dynamic vs java-static [$cond]")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter [$cond]")
    }

    /** The discriminator: the kernel `(1/0.3)^64` is BELOW the split, so the rule is silent; the old actual's
     *  `1/(0.3^64)` was above it and wrongly fired. */
    @Test fun negativePowerReciprocalFormAtTheSplit() =
        agree(m.num(RATE).asNumber().toThePowerOf(Dsl.number("-64")).gt(SPLIT), "0.30", "0.3^-64 vs the form-splitting threshold")

    @Test fun negativePowerFiresWellBelow() =
        agree(m.num(RATE).asNumber().toThePowerOf(Dsl.number("-64")).gt(BELOW), "0.30", "0.3^-64 ≫ 1e6 → both fire")

    @Test fun negativePowerSilentWellAbove() =
        agree(m.num(RATE).asNumber().toThePowerOf(Dsl.number("-64")).gt(ABOVE), "0.30", "0.3^-64 ≪ 1e40 → both silent")

    @Test fun positivePowerUsesBoundedWorkingPrecisionAtObservableSplit() {
        val threshold = "569020148731655962502965624918777104.604660732935865"
        agree(m.num(RATE).asNumber().toThePowerOf(Dsl.number("52")).gt(threshold), "4.870781059298",
            "bounded-work power is below the exact-then-round split")
    }

    @Test fun additionAndSubtractionUseMathContext50() {
        val almostFiftyNines = Dsl.number("99999999999999999999999999999999999999999999999999")
        val roundedSum = "100000000000000000000000000000000000000000000000010"
        agree(m.field(RATE).filled().and(almostFiftyNines.plus(6).eq(roundedSum)), "1", "addition rounds at 50 digits")

        agree(
            m.field(RATE).filled().and(Dsl.number(roundedSum).minus(1).eq(roundedSum)),
            "1",
            "subtraction re-rounds a 50-significant-digit value instead of retaining its exact low digit",
        )
    }

    @Test fun divisionUsesOneRoundingAtObservableSplit() {
        val scaled = Dsl.number("17980052662979")
            .dividedBy(Dsl.number("368740497464577"))
            .times(Dsl.number("10000000000000000000000000000000000000000"))
        agree(
            m.field(RATE).filled().and(scaled.gt("487607213924373767323571753764048881649.035317946475")),
            "1",
            "one-round division is below the double-round split",
        )
    }

    @Test fun powerDomainEndpointsAndQuietInvalidControls() {
        val exponent = m.num(EXPONENT).asNumber()
        val one = Dsl.number("1")
        val zero = Dsl.number("0")

        agree(m.field(RATE).filled().and(one.toThePowerOf(exponent).gt(0)), mapOf(RATE to "1", EXPONENT to "1000"),
            "+1000 is admitted", expected = true)
        agree(m.field(RATE).filled().and(one.toThePowerOf(exponent).gt(0)), mapOf(RATE to "1", EXPONENT to "-1000"),
            "-1000 is admitted", expected = true)
        agree(m.field(RATE).filled().and(one.toThePowerOf(exponent).gt(0)), mapOf(RATE to "1", EXPONENT to "1001"),
            "+1001 is a quiet invalid runtime power", expected = false)
        agree(m.field(RATE).filled().and(one.toThePowerOf(exponent).gt(0)), mapOf(RATE to "1", EXPONENT to "-1001"),
            "-1001 is a quiet invalid runtime power", expected = false)
        agree(m.field(RATE).filled().and(zero.toThePowerOf(exponent).gt(0)), mapOf(RATE to "1", EXPONENT to "0"),
            "0^0 is one", expected = true)
        agree(m.field(RATE).filled().and(zero.toThePowerOf(exponent).gt(0)), mapOf(RATE to "1", EXPONENT to "-1"),
            "0^-1 is a quiet invalid runtime power", expected = false)
    }
}
