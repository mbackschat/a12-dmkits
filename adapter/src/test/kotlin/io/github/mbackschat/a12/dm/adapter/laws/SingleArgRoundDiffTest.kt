package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the Round family's OPTIONAL
 * decimal-places argument (the IG56 E3 crash class, unmasked by IF100: a real workspace implicit validation
 * carries a single-arg `RoundDown([X])`, and the interpreter's `roundExpr` read `args[1]` unconditionally —
 * `IndexOutOfBoundsException`, aborting whole instances). The kernel grammar (`rundenOperOp`/`rundenOp`) makes
 * the digits OPTIONAL on BOTH forms — `RoundX(operation[, digits])` and `RoundXValue(field[, digits])` — and
 * `roundValue` also hardcoded 0 places even when a digits argument IS present (the same class's sibling).
 */
class SingleArgRoundDiffTest {

    private companion object {
        const val AMOUNT = "/Ledger/Amount"
    }

    private val base = DocumentModelDraft.named("ledger").locale("en_US")
        .rootGroup("Ledger") { g ->
            g.numberField("Amount") { it.fractionDigits(0, 3) }
            g.numberField("Result") { it.fractionDigits(0, 0) }
            g.numberField("CentsResult") { it.fractionDigits(0, 2) }
        }.build().toJson()

    /** The computed outcome of [operation] into [target] over Amount = [amount], through all three engines. */
    private fun triOutcome(operation: String, amount: String, target: String = "Result"): String {
        val RESULT = "/Ledger/$target"
        val kModel = RuntimeLaws.withComputation(base, "/Ledger", "RoundComp", "../$target", operation)
        val def = ModelLoader.load(kModel)
        fun kDoc() = RuntimeLaws.doc().group("/Ledger", 1).field(RESULT, "999", 1, 1).field(AMOUNT, amount, 1, 1)
        val groovy = RuntimeLaws.compute(kModel, kDoc()).firstOrNull { it.pointer() == "/Ledger[1]/$target" }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc())).firstOrNull { it.startsWith("$RESULT|") }
            ?.substringAfter("$RESULT|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val iDoc = def.newDocument().group("/Ledger", 1).field(RESULT, "999", 1, 1).field(AMOUNT, amount, 1, 1)
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == RESULT }
            .let { c -> when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            } }
        assertEquals(groovy, javaStatic, "$operation over $amount — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$operation over $amount — kernel vs interpreter")
        return groovy
    }

    // ── the crash shape: the OPERATION form without the digits argument ─────────────────────────────────────

    @Test fun singleArgRoundDownIsZeroPlaces() =
        assertEquals("VALUE|5", triOutcome("RoundDown([Amount])", "5.75"), "RoundDown 1-arg → floor to integer")

    @Test fun singleArgRoundUpIsZeroPlaces() =
        assertEquals("VALUE|6", triOutcome("RoundUp([Amount])", "5.25"), "RoundUp 1-arg → ceiling to integer")

    @Test fun singleArgRoundAccountingIsZeroPlaces() =
        assertEquals("VALUE|6", triOutcome("RoundAccounting([Amount])", "5.5"), "RoundAccounting 1-arg → half-up to integer")

    // ── the sibling: the FIELD-VALUE form WITH a digits argument (previously hardcoded to 0) ────────────────

    @Test fun roundValueHonorsAPresentDigitsArgument() =
        assertEquals("VALUE|5.13", triOutcome("RoundAccountingValue(Amount, 2)", "5.125", target = "CentsResult"),
            "the field form takes the optional digits too")

    @Test fun roundValueWithoutDigitsStaysInteger() =
        assertEquals("VALUE|6", triOutcome("RoundAccountingValue(Amount)", "5.5"))

    /** The crash context itself: the single-arg Round inside a validation comparison must evaluate, not throw. */
    @Test fun singleArgRoundInsideARuleComparison() {
        val kModel = RuntimeLaws.withRule(base, "/Ledger", "R", "../Amount", "BIGR",
            "FieldFilled(Amount) And RoundDown([Amount]) > 5")
        val def = ModelLoader.load(kModel)
        fun fires(v: String): Pair<Boolean, Boolean> {
            val kDoc = RuntimeLaws.doc().group("/Ledger", 1).field(AMOUNT, v, 1, 1)
            val iDoc = def.newDocument().group("/Ledger", 1).field(AMOUNT, v, 1, 1)
            return RuntimeLaws.messages(kModel, kDoc).any { it.code() == "BIGR" } to
                DmInterpreter(def).validateFull(iDoc).any { it.code == "BIGR" }
        }
        assertEquals(fires("6.10").first, fires("6.10").second, "fires side agrees (6.10 → 6 > 5)")
        assertEquals(true, fires("6.10").first, "kernel fires at RoundDown(6.10)=6 > 5")
        assertEquals(fires("5.90").first, fires("5.90").second, "silent side agrees (5.90 → 5)")
        assertEquals(false, fires("5.90").first)
    }
}
