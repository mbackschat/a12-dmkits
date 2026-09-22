package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + QUAD-check for the STORED-FORM rendering of a computed NUMBER (IF82, was IG53). The kernel
 * renders a computed number padded to at least the target field's `minFractionalDigits` — its
 * `NumberConversion.convertNumberToString`/`determineFractionalDigits` rule:
 * `fractionalDigits = max(stripTrailingZeros().scale(), minFractionalDigits)` (`decimalPlacesOptional` defaults
 * to false, so `minFractionalDigits` is honored; capped by the 15-total-digit rule). The interpreter previously
 * rendered the canonical (trailing-zeros-stripped) form via `Dec.display()`, so a `minFractionalDigits`-≥1 target
 * got `10`/`0`/`5.5` where the kernel stores `10.00`/`0.00`/`5.50`.
 *
 * The QUAD-check pins that the **two kernel compute paths agree** — the legacy V1 `compute` AND the modern
 * DocumentV2 `computeModern` (the corpus capture/replay path, CONFORMANCE-CORPUS-SPEC §9/§13) both use the ONE
 * shared conversion formatter, disproving the IG53-filed "the two paths disagree on trailing zeros" hypothesis —
 * and that the java-static codegen and the interpreter match both. Own-domain (e-commerce): a scale-2 `Total`
 * computed by a `Sum`/`FirstFilledValue` over repeatable `Lines`.
 */
class ComputedNumberScaleDiffTest {

    private val PRICE = "/Cart/Lines/Price"
    private val TOTAL = "/Cart/Total"

    /** A base cart with a scale-`maxFrac` (min-`minFrac`) `Total` and a repeatable scale-2 `Lines/Price`. */
    private fun base(minFrac: Int, maxFrac: Int) = DocumentModelDraft.named("cart").locale("en_US")
        .rootGroup("Cart", Consumer { g: GroupBuilder ->
            g.numberField("Total", Consumer { if (minFrac > 0) it.fractionDigits(minFrac, maxFrac) else it.fractionDigits(maxFrac) })
                .group("Lines", Consumer { l -> l.repeatable(99).numberField("Price", Consumer { it.fractionDigits(2) }) })
        }).build().toJson()

    /** The starred `Lines* / Price` list ref, authored off a throwaway bound model (kind-independent of scale). */
    private val prices = BoundModel.ofJson(base(0, 2)).flatten("/Cart/Lines*/Price")

    /** A `Total` computed by [op], with the given target `(minFrac, maxFrac)`, over a scale-2 `Lines/Price` list. */
    private fun model(op: NumberExpr, minFrac: Int, maxFrac: Int): String {
        val rendered = RuntimeLaws.render("/Cart", op.expression())
        return RuntimeLaws.withComputation(base(minFrac, maxFrac), "/Cart", "TotalComp", "../Total", rendered)
    }

    private fun kernelSig(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED"
        else -> "VALUE|${c.value().orElse("")}"
    }

    private fun kdoc(prices: List<Pair<Int, String>>): RuntimeLaws.Doc {
        val d = RuntimeLaws.doc().group("/Cart", 1)
        prices.forEach { (row, v) -> d.group("/Cart/Lines", 1, row).field(PRICE, v, 1, row, 1) }
        return d
    }

    private fun jOutcome(model: String, prices: List<Pair<Int, String>>): String {
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val raw = engine.computeOutcomesSorted(engine.instance(kdoc(prices))).firstOrNull { it.startsWith("$TOTAL|") }
            ?.substringAfter("$TOTAL|") ?: return "NONE"
        return when (raw) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$raw" }
    }

    private fun iOutcome(model: String, prices: List<Pair<Int, String>>): String {
        val def = ModelLoader.load(model)
        val doc = def.newDocument().group("/Cart", 1)
        prices.forEach { (row, v) -> doc.group("/Cart/Lines", 1, row).field(PRICE, v, 1, row, 1) }
        val c = DmInterpreter(def).compute(doc).firstOrNull { it.path == TOTAL }
        return when (c?.outcome) {
            null -> "NONE"; ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
        }
    }

    /** legacy-compute == modern-compute == java-static == interpreter, and all equal the expected stored form. */
    private fun quad(op: NumberExpr, minFrac: Int, maxFrac: Int, prices: List<Pair<Int, String>>, expected: String, msg: String) {
        val model = model(op, minFrac, maxFrac)
        val legacy = kernelSig(RuntimeLaws.compute(model, kdoc(prices)).firstOrNull { it.path() == TOTAL })
        val modern = kernelSig(RuntimeLaws.computeModern(model, kdoc(prices), null).firstOrNull { it.path() == TOTAL })
        val javaStatic = jOutcome(model, prices)
        val interp = iOutcome(model, prices)
        assertEquals(legacy, modern, "$msg — legacy compute vs modern computeModern (the two kernel paths agree)")
        assertEquals(legacy, javaStatic, "$msg — groovy vs java-static")
        assertEquals(expected, legacy, "$msg — kernel stored form")
        assertEquals(legacy, interp, "$msg — kernel vs interpreter")
    }

    // ── minFractionalDigits = 2 (fixed scale): the kernel pads; the interpreter must too ─────────────────
    @Test fun wholeResultPadsToMinScale() =
        quad(Dsl.sum(prices), 2, 2, listOf(1 to "5.00", 2 to "5.00"), "VALUE|10.00", "Sum=10 into a min=2 field → 10.00")
    @Test fun oneTrailingZeroPadsToMinScale() =
        quad(Dsl.sum(prices), 2, 2, listOf(1 to "5.50"), "VALUE|5.50", "Sum=5.5 into a min=2 field → 5.50")
    @Test fun emptySumZeroPadsToMinScale() =
        quad(Dsl.sum(prices), 2, 2, emptyList(), "VALUE|0.00", "Sum(empty)=0 into a min=2 field → 0.00")
    @Test fun firstFilledEmptyZeroPadsToMinScale() =
        quad(Dsl.firstFilledNumber(prices), 2, 2, emptyList(), "VALUE|0.00", "FirstFilled(empty)=0 into a min=2 field → 0.00 (the IF80 shape)")
    @Test fun exactScaleUnchanged() =
        quad(Dsl.sum(prices), 2, 2, listOf(1 to "3.33", 2 to "3.34"), "VALUE|6.67", "Sum=6.67 → 6.67 (already 2 frac)")

    // ── minFractionalDigits = 0 (the default): canonical form, NO padding (control — the unchanged behavior) ─
    @Test fun noMinScaleStaysCanonicalWhole() =
        quad(Dsl.sum(prices), 0, 2, listOf(1 to "5.00", 2 to "5.00"), "VALUE|10", "Sum=10 into a min=0 field → 10 (canonical)")
    @Test fun noMinScaleStaysCanonicalTrailing() =
        quad(Dsl.sum(prices), 0, 2, listOf(1 to "5.50"), "VALUE|5.5", "Sum=5.5 into a min=0 field → 5.5 (canonical)")
    @Test fun noMinScaleEmptyZero() =
        quad(Dsl.sum(prices), 0, 2, emptyList(), "VALUE|0", "Sum(empty)=0 into a min=0 field → 0 (canonical)")

    // ── minFractionalDigits = 1 (max = 2, matching the scale-2 Sum): pad to 1, but KEEP extra digits up to max ─
    @Test fun minOnePadsWhole() =
        quad(Dsl.sum(prices), 1, 2, listOf(1 to "5.00", 2 to "5.00"), "VALUE|10.0", "Sum=10 into min=1 → 10.0")
    @Test fun minOneKeepsExtraDigits() =
        quad(Dsl.sum(prices), 1, 2, listOf(1 to "5.55", 2 to "0.01"), "VALUE|5.56", "Sum=5.56 into min=1 → 5.56 (2 frac ≥ min 1)")
}
