package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — a12-kernel-lean's
 * `SPEC-2026-09-04-04`, and it **corrects the axis their clause names**.
 *
 * **Their claim.** A per-row target whose filtered candidate set is **empty** produces no computation outcome at
 * all, "where a precondition-cleared or source-invalid producer is reported present and cleared". Reproduced
 * here, both rows hold — and the entry's own separator rests on comparing the empty-candidate target against a
 * *cleared* one.
 *
 * **What that comparison does not hold fixed.** A no-value outcome's REPORTED shape is decided by the target
 * cell's prior state, not by the ground of the no-value: the kernel's change projection reports a **stale-filled**
 * target as `CLEARED` and an **absent** one not at all ([IF123](../../../../../../../../docs/INTERPRETER-FINDINGS.md),
 * measured from a12-kernel-lean's own 13-case receipt). An empty-candidate target that nothing seeded and a
 * cleared producer over a stale value therefore differ in **two** variables at once, and the seed is the one that
 * decides the shape.
 *
 * **The 2×2 below is the control that separates them**, and it is within one model and one document per cell —
 * the same three rows, the same channel, one variable moved at a time:
 *
 * <pre>
 *                     | empty candidate set | unmet precondition
 *   target seeded      |      CLEARED        |      CLEARED
 *   target absent      |     no outcome      |     no outcome
 * </pre>
 *
 * So the absence is real and worth transporting, but it belongs to the **absent target**, not to the empty
 * candidate set — and a consumer told to expect a missing row specifically for an empty filter would be wrong in
 * both directions. What IS specific to the ground is nothing observable in this channel.
 *
 * **The addressing control.** The canonical pointer for a first repeatable instance can render without its index,
 * so an absence at row 1 is easy to misread. A second document with the prices DESCENDING moves the
 * empty-candidate row to the front; the absence moves with it and the other two answer at their own indices.
 *
 * **Exact per-row values, so each document refutes the reversed marker reading alone.** Over ascending prices
 * `10, 30, 20` with skus `A, B, C` the answers are `B`, none, `B`; the reversed reading predicts `nothing, A, A`.
 * Descending gives none, `A`, `A` against a predicted `B, B, nothing`.
 *
 * **The dependent row is the half a measurement from the reporting channel alone gets wrong** — their entry
 * records correcting exactly that before sending. `Length` over the no-value row computes `0` and is present on
 * every row, whichever cell of the 2×2 produced it, so the two absences are indistinguishable downstream and
 * neither is poison.
 *
 * Own domain: an e-commerce basket where each line names the sku of the cheapest line priced above it.
 */
class EmptyCandidateSetOutcomeDiffTest {

    private companion object {
        const val LINES = "/Basket/Lines"
        /** This channel renders the root index and keeps `[1]`, unlike the canonical message address. */
        fun at(row: Int, field: String) = "/Basket[1]/Lines[$row]/$field"
        const val PRICE = "$LINES/Price"
        const val SKU = "$LINES/Sku"
        const val NEXT_SKU = "$LINES/NextHigherSku"
        const val NEXT_LEN = "$LINES/NextHigherLen"
        const val FLAG = "$LINES/Flag"
        const val MARKER = "$LINES/Marker"

        /** Deliberately not a value either computation can produce, so a seeded target's write is a CHANGE. */
        const val SEED = "SEEDED"
        val ASCENDING = listOf("10", "30", "20")
        val DESCENDING = listOf("30", "20", "10")
    }

    private val base = DocumentModelDraft.named("basket-empty-candidate").locale("en_US")
        .rootGroup("Basket") { basket ->
            basket.group("Lines") { lines ->
                lines.repeatable(9)
                    .numberField("Price") { it.fractionDigits(0, 0) }
                    .stringField("Sku") { }
                    .stringField("NextHigherSku") { }
                    .numberField("NextHigherLen") { it.fractionDigits(0, 0) }
                    .stringField("Flag") { }
                    .stringField("Marker") { }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)
    private val render = DslRenderer()

    /** `FirstFilledValue(the flatten of Lines* then Sku Having [Lines/Price] > [$Lines/Price])`. */
    private val nextHigherSku = render.render(
        Dsl.firstFilledString(
            m.flatten("$LINES*/Sku").having(m.num(PRICE).gt(m.num(PRICE).outer())),
        ).expression(),
    )

    /** The dependent: the length of the computed cell above, so a no-value row is read one step further. */
    private val nextHigherLen = render.render(m.str(NEXT_SKU).length().expression())

    /** The other no-value GROUND: a precondition no row satisfies, over the same carrier kind. */
    private val flagPrecondition = render.render(Dsl.fieldFilled(m.field(MARKER)))
    private val flagOperation = render.render(m.str(SKU).asString().expression())

    private val model: String = run {
        val withNext = RuntimeLaws.withComputation(base, LINES, "NextHigherComp", "../NextHigherSku", nextHigherSku)
        val withLen = RuntimeLaws.withComputation(withNext, LINES, "NextHigherLenComp", "../NextHigherLen",
            nextHigherLen)
        RuntimeLaws.withTieredComputation(withLen, LINES, "FlagComp", "../Flag", flagPrecondition,
            listOf(RuntimeLaws.CompAlt(null, flagOperation)))
    }

    @Test
    fun theReportedShapeOfANoValueOutcomeFollowsTheTargetSeedRatherThanItsGround() {
        // Row 2 keeps no candidate priced above 30; no row satisfies Flag's precondition. One variable per cell.
        // Flag's precondition is unmet on EVERY row, so its cleared set is all three; the empty candidate set
        // is row 2's alone. That asymmetry is the grounds' only difference and it is not in the SHAPE.
        assertEquals(
            listOf(
                at(1, "Flag") + "|CLEARED",
                at(2, "Flag") + "|CLEARED",
                at(2, "NextHigherSku") + "|CLEARED",
                at(3, "Flag") + "|CLEARED",
            ),
            noValueRowsOf(triOutcomes(ASCENDING, seedNext = true, seedFlag = true)),
            "both grounds report CLEARED when the target carries a stale value",
        )
        // Asserted UNFILTERED, not through noValueRowsOf: filtering to `|CLEARED` would let a row reported
        // `VALUE|""` satisfy an "reports nothing" expectation, and "nothing at all" is exactly the claim. The
        // empty-candidate ground is separately pinned by the complete value-bearing list below; this is the
        // only place the PRECONDITION ground's absence is stated, so it needs the whole list.
        assertEquals(
            listOf(
                at(1, "NextHigherLen") + "|VALUE|1",
                at(1, "NextHigherSku") + "|VALUE|B",
                at(2, "NextHigherLen") + "|VALUE|0",
                at(3, "NextHigherLen") + "|VALUE|1",
                at(3, "NextHigherSku") + "|VALUE|B",
            ),
            triOutcomes(ASCENDING, seedNext = false, seedFlag = false),
            "both grounds report NOTHING when the target is absent — so the shape is the seed's, not the " +
                "ground's. The COMPLETE list is what makes that an absence: no `Flag` instance appears at " +
                "all (the precondition ground) and row 2 carries no `NextHigherSku` (the empty-candidate " +
                "ground), while every row that HAS a value reports it",
        )
        assertEquals(
            listOf(at(1, "Flag") + "|CLEARED", at(2, "Flag") + "|CLEARED", at(3, "Flag") + "|CLEARED"),
            noValueRowsOf(triOutcomes(ASCENDING, seedNext = false, seedFlag = true)),
            "the diagonal a12-kernel-lean's clause measured: absent empty-candidate target beside a stale " +
                "cleared one — which moves BOTH variables and is why the row above is the control",
        )
        assertEquals(
            listOf(at(2, "NextHigherSku") + "|CLEARED"),
            noValueRowsOf(triOutcomes(ASCENDING, seedNext = true, seedFlag = false)),
            "the opposite diagonal reverses which ground looks like the absent one, which no account of the " +
                "GROUND can produce",
        )
    }

    @Test
    fun theValueBearingRowsAnswerExactlyAndRefuteTheReversedMarkerReading() {
        assertEquals(
            listOf(at(1, "NextHigherSku") + "|VALUE|B", at(3, "NextHigherSku") + "|VALUE|B"),
            triOutcomes(ASCENDING, seedNext = false, seedFlag = false).filter { it.contains("/NextHigherSku|") },
            "rows 1 and 3 select the cheapest line priced above them; reversed predicts nothing, A, A",
        )
        assertEquals(
            listOf(at(2, "NextHigherSku") + "|VALUE|A", at(3, "NextHigherSku") + "|VALUE|A"),
            triOutcomes(DESCENDING, seedNext = false, seedFlag = false).filter { it.contains("/NextHigherSku|") },
            "descending moves the no-candidate row to the FRONT and the absence moves with it; reversed " +
                "predicts B, B, nothing",
        )
    }

    @Test
    fun aDependentReadsAnyNoValueRowAsEmptyRatherThanAsPoison() {
        val lengths = { outcomes: List<String> -> outcomes.filter { it.contains("/NextHigherLen|") } }

        assertEquals(
            listOf(
                at(1, "NextHigherLen") + "|VALUE|1",
                at(2, "NextHigherLen") + "|VALUE|0",
                at(3, "NextHigherLen") + "|VALUE|1",
            ),
            lengths(triOutcomes(ASCENDING, seedNext = false, seedFlag = false)),
            "the dependent is present on ALL THREE rows and answers 0 over the no-value one",
        )
        assertEquals(
            listOf(
                at(1, "NextHigherLen") + "|VALUE|1",
                at(2, "NextHigherLen") + "|VALUE|0",
                at(3, "NextHigherLen") + "|VALUE|1",
            ),
            lengths(triOutcomes(ASCENDING, seedNext = true, seedFlag = false)),
            "and answers 0 over the SEEDED no-value row too — the dependent reads the outcome, never the " +
                "stale cell, so the reported split is invisible downstream",
        )
        assertEquals(
            listOf(
                at(1, "NextHigherLen") + "|VALUE|0",
                at(2, "NextHigherLen") + "|VALUE|1",
                at(3, "NextHigherLen") + "|VALUE|1",
            ),
            lengths(triOutcomes(DESCENDING, seedNext = false, seedFlag = false)),
            "the dependent pair reproduces at row 1 on the reordered document",
        )
    }

    // ---- harness ---------------------------------------------------------------------------------

    private fun noValueRowsOf(outcomes: List<String>) = outcomes.filter { it.endsWith("|CLEARED") }

    /** Every computed outcome as `pointer|OUTCOME[|value]`, agreed by both kernel strategies and the interpreter. */
    private fun triOutcomes(prices: List<String>, seedNext: Boolean, seedFlag: Boolean): List<String> {
        val def = ModelLoader.load(model)
        val skus = listOf("A", "B", "C")
        val label = "prices=$prices seedNext=$seedNext seedFlag=$seedFlag"

        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Basket", 1)
            i.group("/Basket", 1)
            prices.forEachIndexed { index, price ->
                val row = index + 1
                k.group(LINES, 1, row).field(PRICE, price, 1, row, 1).field(SKU, skus[index], 1, row, 1)
                i.group(LINES, 1, row).field(PRICE, price, 1, row, 1).field(SKU, skus[index], 1, row, 1)
                if (seedNext) { k.field(NEXT_SKU, SEED, 1, row, 1); i.field(NEXT_SKU, SEED, 1, row, 1) }
                if (seedFlag) { k.field(FLAG, SEED, 1, row, 1); i.field(FLAG, SEED, 1, row, 1) }
            }
        }

        val kDoc = RuntimeLaws.doc()
        val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.compute(model, kDoc)
            .map { signature(it.pointer(), it.cleared(), it.errored(), it.value().orElse("")) }.sorted()

        val engine = RuntimeLaws.kernelJavaEngine(model)
        val kDoc2 = RuntimeLaws.doc()
        val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.compute(engine.instance(kDoc2))
            .map { signature(it.pointer(), it.cleared(), it.errored(), it.value().orElse("")) }.sorted()

        // `DmInterpreter.compute` reports EVERY computed instance by contract, while the kernel's own result is
        // its three-channel CHANGE projection — so a raw comparison is an altitude mismatch, not a divergence.
        // It reads as one: it makes the interpreter look like it invents a CLEARED for every unseeded no-value
        // target, which is exactly the shape this test is about. `iDoc2` is built identically and never
        // computed, so it is the untouched INPUT side the projection needs.
        val interpreted = kernelComputeGranularity(
            DmInterpreter(def).compute(iDoc).map {
                signature(
                    formatPointer(it.path, it.indices),
                    it.outcome == ComputeOutcome.CLEARED,
                    it.outcome == ComputeOutcome.ERRORED,
                    it.value.orEmpty(),
                )
            },
            iDoc2,
            def,
        ).sorted()

        assertEquals(groovy, javaStatic, "$label — groovy-dynamic vs generated Java")
        assertEquals(groovy, interpreted, "$label — kernel vs interpreter (both at kernel granularity)")
        return groovy
    }

    private fun signature(pointer: String, cleared: Boolean, errored: Boolean, value: String): String = when {
        cleared -> "$pointer|CLEARED"
        errored -> "$pointer|ERRORED|$value"
        else -> "$pointer|VALUE|$value"
    }
}
