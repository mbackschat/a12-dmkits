package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Both kernel strategies anchor the exact one-pass arithmetic lowering. These witnesses distinguish
 * the lowered tree from direct authored-tree evaluation and from an accidental fixed-point rewrite.
 */
class ArithmeticLoweringDiffTest {

    private companion object {
        const val ANCHOR = "/Probe/Anchor"
        const val CODE = "ARITH_LOWER"
    }

    private val base = DocumentModelDraft.named("arithmetic-lowering").locale("en_US")
        .rootGroup("Probe") { it.stringField("Anchor") { } }
        .build().toJson()
    private val model = BoundModel.ofJson(base)

    @Test
    fun immediateDivisionRootIsPulledInsteadOfEvaluatingTheAuthoredTreeDirectly() {
        val scaled = Dsl.number("6885150.4383834")
            .times(Dsl.number("4387041.2123023").dividedBy(Dsl.number("8755031.9106009")))
            .times(Dsl.number("10").toThePowerOf(Dsl.number("30")))
        agree(
            model.field(ANCHOR).filled()
                .and(scaled.ge("3450066091651295731226048519919816882.1271814228636")),
            expected = true,
            label = "the one-pass value reaches …8636; direct authored evaluation ends …8635",
        )
    }

    @Test
    fun newlyCreatedProductsAreNotRevisitedToASecondPass() {
        val once = Dsl.number("9630076.6479982")
            .times(
                Dsl.number("4759545.2104432")
                    .dividedBy(Dsl.number("3619560.0560554"))
                    .dividedBy(Dsl.number("5761403.5081808")),
            )
            .plus(0)
            .times(Dsl.number("10").toThePowerOf(Dsl.number("30")))
        agree(
            model.field(ANCHOR).filled()
                .and(once.lt("2197916152576526711637222821427.5545826651492166571")),
            expected = true,
            label = "one pass ends …6570; direct evaluation ends …6571 and a fixed point ends …6572",
        )
    }

    @Test
    fun threeSeparatelyRoundedThirdsArePreRoundedBeforeTargetFlooring() {
        val third = Dsl.number("1").dividedBy(Dsl.number("3"))
        val rounded = Dsl.roundDown(third.plus(third).plus(third), 0)
        agree(
            model.field(ANCHOR).filled().and(rounded.eq(1)),
            expected = true,
            label = "scale-19 HALF_UP turns the precision-50 sum 1−10^-50 into 1 before floor",
        )
    }

    private fun agree(condition: Condition, expected: Boolean, label: String) {
        val rendered = DslRenderer().render(condition)
        val kModel = RuntimeLaws.withRule(base, "/Probe", "R", "../Anchor", CODE, rendered)
        val kDoc = RuntimeLaws.doc().group("/Probe", 1).field(ANCHOR, "present", 1, 1)
        val groovy = RuntimeLaws.messages(kModel, kDoc).any { it.code() == CODE }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains(CODE)

        val definition = ModelLoader.load(kModel)
        val iDoc = definition.newDocument().group("/Probe", 1).field(ANCHOR, "present", 1, 1)
        val interpreter = DmInterpreter(definition).validateFull(iDoc).any { it.code == CODE }

        assertEquals(groovy, javaStatic, "$label — dynamic-Groovy vs static-Java")
        assertEquals(groovy, interpreter, "$label — kernel vs interpreter")
        assertEquals(expected, groovy, "$label — groovy-dynamic [$rendered]")
    }
}
