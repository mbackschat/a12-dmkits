package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a `DateRange(start, finish)` CONSTRUCTION assigned to a **yearless fragment** DATE_RANGE target
 * on a model with **no declared Base Year** (SPEC-2026-08-19-05). The stored result keeps the two ordered
 * component labels the profile declares; nothing manufactures a year or an instant to carry them.
 *
 * The four outcome arms are the generic computation rules seen through this target: ordered inputs store a
 * value; inverted inputs still produce a stored attempt, which the target's own formal check then rejects, so
 * the attempted text is RETAINED as ERRORED rather than dropped; an empty operand is no computed value and
 * clears a seeded target; a malformed operand likewise clears while the formal-input inventory reports it.
 *
 * Own domain (seasonal insurance cover). The operation is authored with the rulekit typed DSL and rendered
 * group-relative to the string both kernel strategies and the interpreter consume.
 */
class ConstructedRangeTargetDiffTest {

    private val base = DocumentModelDraft.named("seasonal-target").locale("en_US")
        .rootGroup("Cover") { cover ->
            cover.dateFragmentField("FromMonth") { it.fragmentFormat("MM") }
            cover.dateFragmentField("ToMonth") { it.fragmentFormat("MM") }
            cover.dateRangeField("MonthWindow") { it.format("MM").separator("/") }
            cover.dateFragmentField("FromDay") { it.fragmentFormat("MM-dd") }
            cover.dateFragmentField("ToDay") { it.fragmentFormat("MM-dd") }
            cover.dateRangeField("DayWindow") { it.format("MM-dd").separator("/") }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)
    private fun renderOp(c: Calculation): String = DslRenderer(ConditionLanguage.EN, ROOT).render(c)

    private val monthModel = RuntimeLaws.withComputation(base, ROOT, "MonthWindowComp", "../MonthWindow",
        renderOp(Dsl.dateRange(m.dateFragment("$ROOT/FromMonth"), m.dateFragment("$ROOT/ToMonth"))))
    private val dayModel = RuntimeLaws.withComputation(base, ROOT, "DayWindowComp", "../DayWindow",
        renderOp(Dsl.dateRange(m.dateFragment("$ROOT/FromDay"), m.dateFragment("$ROOT/ToDay"))))

    /** `outcome|value` for [target] on dynamic Groovy, generated Java, and the interpreter — all three pinned
     *  to [expected], so an agreeing-but-wrong trio cannot pass. Cells with a null value are left absent. */
    private fun agree(modelJson: String, target: String, expected: String, vararg cells: Pair<String, String?>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        cells.forEach { (path, value) -> value?.let { kernelDocument.field(path, it, 1, 1) } }
        val dynamic = signature(RuntimeLaws.compute(modelJson, kernelDocument).firstOrNull { it.path() == target })
        val engine = RuntimeLaws.kernelJavaEngine(modelJson)
        val generated = signature(engine.compute(engine.instance(kernelDocument)).firstOrNull { it.path() == target })

        val definition = ModelLoader.load(modelJson)
        val document = definition.newDocument().group(ROOT, 1)
        cells.forEach { (path, value) -> value?.let { document.field(path, it, 1, 1) } }
        val computed = DmInterpreter(definition).compute(document).firstOrNull { it.path == target }
        val interpreted = when (computed?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED|${computed.value}"
            ComputeOutcome.VALUE -> "VALUE|${computed.value}"
        }

        assertEquals(expected, dynamic, "normative dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun signature(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED|${c.value().orElse("")}"
        else -> "VALUE|${c.value().orElse("")}"
    }

    @Test
    fun orderedMonthComponentsStoreTheOrderedPair() =
        agree(monthModel, "$ROOT/MonthWindow", "VALUE|03/07",
            "$ROOT/FromMonth" to "03", "$ROOT/ToMonth" to "07")

    @Test
    fun orderedMonthDayComponentsStoreTheOrderedPair() =
        agree(dayModel, "$ROOT/DayWindow", "VALUE|03-01/07-31",
            "$ROOT/FromDay" to "03-01", "$ROOT/ToDay" to "07-31")

    @Test
    fun invertedMonthComponentsRetainTheAttemptedTextAsErrored() =
        agree(monthModel, "$ROOT/MonthWindow", "ERRORED|07/03",
            "$ROOT/FromMonth" to "07", "$ROOT/ToMonth" to "03",
            "$ROOT/MonthWindow" to "01/02")

    @Test
    fun anEmptyEndpointClearsASeededTarget() =
        agree(monthModel, "$ROOT/MonthWindow", "CLEARED",
            "$ROOT/FromMonth" to "03", "$ROOT/MonthWindow" to "01/02")

    @Test
    fun aMalformedEndpointClearsASeededTarget() =
        agree(monthModel, "$ROOT/MonthWindow", "CLEARED",
            "$ROOT/FromMonth" to "1x", "$ROOT/ToMonth" to "07",
            "$ROOT/MonthWindow" to "01/02")

    private companion object {
        const val ROOT = "/Cover"
    }
}
