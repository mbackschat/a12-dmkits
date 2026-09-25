package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for what a banded computation's
 * `roundingMode` does at run time (INTERPRETER-FINDINGS IF341), authored through `Computation.Builder.roundingMode`.
 *
 * A non-`Exact` mode is legal only beside a tolerance band ([KF297]), so every carrier here has one. Two carriers,
 * because the mode could act in two places: `ChargedPremium = [BasePremium]` at the same scale with a nonzero
 * fraction, where rounding to the target's digits would change nothing and rounding to a whole number does; and
 * `ChargedPremium = [FineBase]` one digit past the target, the static scale gate suppressed, where an `Exact`
 * computation without a band could not store its value as computed.
 *
 * What the rows lock: the mode rounds what the computation WRITES to a whole number, `RoundUp` toward +∞ and
 * `RoundDown` toward −∞ (the negative rows separate that from rounding away from or toward zero); `Exact` beside a
 * band rounds the operation down to the target's digits (the negative finer row separates floor from truncation,
 * and the unbanded row, the same model without its band, shows the band is what replaces the error with the floor);
 * and the implicit validation compares the entered value with the operation as authored, neither rounded nor
 * truncated.
 */
class ComputationRoundingModeDiffTest {

    private val base = DocumentModelDraft.named("premium").locale("en_US")
        .rootGroup("Premium") { p ->
            p.numberField("BasePremium") { it.fractionDigits(2) }
                .numberField("FineBase") { it.fractionDigits(3) }
                .numberField("ChargedPremium") { it.fractionDigits(2) }
        }
        .build()
        .toJson()

    private val bound = BoundModel.ofJson(base)

    /** The charged premium follows [operand] within one unit, rounded by [mode]; [banded] false is the same model
     *  with only the band removed, the control for what the band itself changes. */
    private fun model(operand: String, mode: RoundingMode, banded: Boolean = true): String {
        var builder = bound.computation(bound.num(CHARGED)).name(NAME)
            .calc(bound.num(operand).value(), if (banded) ToleranceBand.TR1 else null)
            .roundingMode(mode)
            .message(Dsl.en("The charged premium follows the base premium."))
        if (operand == FINE) builder = builder.errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")
        return admitted(builder)
    }

    /** Two guarded, banded alternatives over [BASE] (A12 refuses mixing a guarded and an unconditional one), so
     *  either can be the one selected, both rounded by [mode]. */
    private fun tieredModel(mode: RoundingMode): String {
        val basePremium = bound.num(BASE)
        return admitted(bound.computation(bound.num(CHARGED)).name(NAME)
            .`when`(basePremium.gt(5)).calc(basePremium.value(), ToleranceBand.TR1)
            .`when`(basePremium.filled()).calc(basePremium.value(), ToleranceBand.TR1)
            .roundingMode(mode)
            .message(Dsl.en("The charged premium follows the base premium.")))
    }

    private fun admitted(builder: Computation.Builder): String {
        val json = TypedRules.inject(base, builder.build())
        assertEquals(emptyList<String>(),
            RuntimeLaws.consistencyMessages(json).filter { it.startsWith("ERROR") }, "the kernel admits the model")
        return json
    }

    /** The three engines' computed outcome for the target, from [operandValue] with nothing entered. */
    private fun computed(json: String, operand: String, operandValue: String): String {
        val def = ModelLoader.load(json)
        val seeded = { b: Both ->
            val root = b.group(ROOT, 1)
            if (operandValue.isNotEmpty()) root.field(operand, operandValue, 1, 1)
        }
        fun kernel(c: RuntimeLaws.Computed) =
            if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"
        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also { seeded(it) }
        val groovy = RuntimeLaws.compute(json, dynamic.kDoc).joinToString(";") { kernel(it) }
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also { seeded(it) }.kDoc
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(staticDoc)).joinToString(";") {
            when (val v = it.substringAfter('|')) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v" }
        }
        val interpreter = runCatching { DmInterpreter(def).compute(dynamic.iDoc) }.fold(
            onSuccess = { results ->
                results.joinToString(";") {
                    when (it.outcome) {
                        ComputeOutcome.CLEARED -> "CLEARED"
                        ComputeOutcome.ERRORED -> "ERRORED"
                        ComputeOutcome.VALUE -> "VALUE|${it.value}"
                    }
                }
            },
            onFailure = { "THREW ${it.message}" },
        )
        return "groovy=$groovy java=$javaStatic interpreter=$interpreter"
    }

    /** Whether each engine's implicit validation reports the computation, with [entered] typed into the target. */
    private fun fires(json: String, operand: String, operandValue: String, entered: String): String {
        val def = ModelLoader.load(json)
        val seeded = { b: Both -> b.group(ROOT, 1).field(operand, operandValue, 1, 1).field(CHARGED, entered, 1, 1) }
        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also { seeded(it) }
        val groovy = RuntimeLaws.firedCodes(json, dynamic.kDoc).contains(NAME)
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also { seeded(it) }.kDoc
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val javaStatic = engine.firedCodesSorted(engine.instance(staticDoc)).contains(NAME)
        val interpreter = runCatching { DmInterpreter(def).validate(dynamic.iDoc) }.fold(
            onSuccess = { outcome ->
                if (outcome.unsupported.isNotEmpty()) "UNSUPPORTED" else outcome.messages.any { it.code == NAME }.toString()
            },
            onFailure = { "THREW ${it.message}" },
        )
        return "groovy=$groovy java=$javaStatic interpreter=$interpreter"
    }

    private data class Case(val operand: String, val mode: RoundingMode, val value: String, val kernel: String,
        val tiered: Boolean = false, val banded: Boolean = true)

    @Test
    fun theModeDecidesWhatTheComputationWrites() {
        val cases = listOf(
            Case(BASE, RoundingMode.EXACT, "1.25", "VALUE|1.25"),
            // The banded floor gives a coarser operand the target's scale; the store rule strips it again.
            Case(BASE, RoundingMode.EXACT, "3", "VALUE|3"),
            Case(BASE, RoundingMode.ROUND_UP, "1.25", "VALUE|2"),
            Case(BASE, RoundingMode.ROUND_DOWN, "1.25", "VALUE|1"),
            Case(BASE, RoundingMode.ROUND_UP, "1.75", "VALUE|2"),
            Case(BASE, RoundingMode.ROUND_DOWN, "1.75", "VALUE|1"),
            Case(BASE, RoundingMode.ROUND_UP, "-1.25", "VALUE|-1"),
            Case(BASE, RoundingMode.ROUND_DOWN, "-1.25", "VALUE|-2"),
            Case(BASE, RoundingMode.ROUND_UP, "3.00", "VALUE|3"),
            Case(BASE, RoundingMode.EXACT, "", "VALUE|0"),
            Case(BASE, RoundingMode.ROUND_UP, "", "VALUE|0"),
            Case(FINE, RoundingMode.EXACT, "1.255", "VALUE|1.25"),
            Case(FINE, RoundingMode.EXACT, "-1.255", "VALUE|-1.26"),
            Case(FINE, RoundingMode.EXACT, "1.255", "ERRORED", banded = false),
            Case(FINE, RoundingMode.ROUND_UP, "1.255", "VALUE|2"),
            Case(FINE, RoundingMode.ROUND_DOWN, "1.255", "VALUE|1"),
            Case(BASE, RoundingMode.ROUND_UP, "7.25", "VALUE|8", tiered = true),
            Case(BASE, RoundingMode.ROUND_UP, "1.25", "VALUE|2", tiered = true))
        val key = { c: Case ->
            "${if (c.tiered) "tiered " else ""}${if (c.banded) "" else "unbanded "}" +
                "${c.operand.substringAfterLast('/')} ${c.mode.wire()} ${c.value}"
        }
        val actual = cases.associate {
            val json = if (it.tiered) tieredModel(it.mode) else model(it.operand, it.mode, it.banded)
            key(it) to computed(json, it.operand, it.value)
        }
        val expected = cases.associate { key(it) to "groovy=${it.kernel} java=${it.kernel} interpreter=${it.kernel}" }
        assertEquals(expected, actual)
    }

    private data class Check(val operand: String, val mode: RoundingMode, val value: String, val entered: String,
        val kernel: Boolean)

    /**
     * The band compares what is entered with what the operation computes before any rounding. Each row names the
     * rival it refutes: comparing with the ROUNDED operation fires `RoundUp` at 0.30 and `RoundDown` at 2.20 and
     * silences both finer rows but `RoundUp`'s; comparing with the TRUNCATED `Exact` operation (1.25 against 0.25)
     * silences the finer `Exact` row.
     */
    @Test
    fun theBandComparesTheEnteredValueWithTheUnroundedOperation() {
        val checks = listOf(
            Check(BASE, RoundingMode.EXACT, "1.25", "0.30", false),
            Check(BASE, RoundingMode.ROUND_UP, "1.25", "0.30", false),
            Check(BASE, RoundingMode.ROUND_DOWN, "1.25", "0.30", false),
            Check(BASE, RoundingMode.EXACT, "1.25", "2.20", false),
            Check(BASE, RoundingMode.ROUND_UP, "1.25", "2.20", false),
            Check(BASE, RoundingMode.ROUND_DOWN, "1.25", "2.20", false),
            Check(FINE, RoundingMode.EXACT, "1.259", "0.25", true),
            Check(FINE, RoundingMode.ROUND_UP, "1.259", "0.25", true),
            Check(FINE, RoundingMode.ROUND_DOWN, "1.259", "0.25", true))
        val key = { c: Check -> "${c.operand.substringAfterLast('/')} ${c.mode.wire()} ${c.value} entered=${c.entered}" }
        val actual = checks.associate { key(it) to fires(model(it.operand, it.mode), it.operand, it.value, it.entered) }
        val expected = checks.associate { key(it) to "groovy=${it.kernel} java=${it.kernel} interpreter=${it.kernel}" }
        assertEquals(expected, actual)
    }

    private companion object {
        const val ROOT = "/Premium"
        const val NAME = "ChargedComp"
        const val BASE = "/Premium/BasePremium"
        const val FINE = "/Premium/FineBase"
        const val CHARGED = "/Premium/ChargedPremium"
    }
}
