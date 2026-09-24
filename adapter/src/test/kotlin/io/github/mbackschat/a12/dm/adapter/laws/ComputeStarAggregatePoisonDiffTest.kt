package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the IG44 **R2** boundary of the compute poison (IF85): a computed STAR CELL that is POISONED
 * (its own computation read an invalid operand) must poison a downstream computation's star-aggregate read of
 * that cell — the kernel's `CalculationCache` invalid-marking makes ANY read of the poisoned cell throw, and a
 * value aggregate feeds every selected row to its combiner, so one poisoned cell aborts the dependent (it is
 * "fehlerhaft berechnet": no value, CLEARED when seeded). The aggregate must NOT treat the poisoned cell as
 * skippable-like-empty and compute over the remaining rows.
 *
 * Own-domain (e-commerce): a per-row `Pricing* / Helper = [Price]` cascade — one row's `Price` is
 * min-fraction-digit-invalid, so that row's `Helper` poisons — read downstream by `MaxValue(Pricing* / Helper)`
 * as an operation and as a PRECONDITION (the R2 minimal's shape). Tri-check groovy-dynamic == java-static ==
 * interpreter; every computed target is seeded so a kernel clear reports.
 */
class ComputeStarAggregatePoisonDiffTest {

    private val PRICE = "/Shop/Pricing/Price"
    private val HELPER = "/Shop/Pricing/Helper"
    private val BEST = "/Shop/Best"
    private val FLAG = "/Shop/Flag"

    private val ROWFLAG = "/Shop/Pricing/RowFlag"

    private val base = DocumentModelDraft.named("shop").locale("en_US")
        .rootGroup("Shop", Consumer { g: GroupBuilder ->
            g.numberField("Best", Consumer { it.fractionDigits(2, 2) })
                .numberField("Flag", Consumer { })
                .group("Pricing", Consumer { p ->
                    p.repeatable(99)
                        .numberField("Price", Consumer { it.fractionDigits(2, 2) })
                        .numberField("Helper", Consumer { it.fractionDigits(2, 2) })
                        .numberField("RowFlag", Consumer { })
                })
        }).build().toJson()

    /** The per-row Helper cascade plus one downstream root computation over the Helper star. */
    private fun model(downstreamTarget: String, downstreamOp: String, precondition: String? = null): String {
        val withHelper = RuntimeLaws.withComputation(base, "/Shop/Pricing", "HelperComp", "../Helper", "[Price]")
        return if (precondition == null)
            RuntimeLaws.withComputation(withHelper, "/Shop", "DownstreamComp", downstreamTarget, downstreamOp)
        else RuntimeLaws.withTieredComputation(withHelper, "/Shop", "DownstreamComp", downstreamTarget, null,
            listOf(RuntimeLaws.CompAlt(precondition, downstreamOp)))
    }

    /** Row r of Pricing with a Price and a stale Helper seed (so a kernel clear of Helper reports). */
    private fun Both.pricingRow(r: Int, price: String): Both =
        group("/Shop/Pricing", 1, r).field(PRICE, price, 1, r, 1).field(HELPER, "1.00", 1, r, 1)

    /** All three engines' outcomes keyed per CELL (`path@pointer` — Helper is per-row); asserts the tri-check. */
    private fun agree(kModel: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.compute(engine.instance(kDoc2)).associate { "${it.path()}@${it.pointer()}" to kSig(it) }
        val interp = DmInterpreter(def).compute(b.iDoc).associate {
            "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it)
        }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    // ── the R2 class: one poisoned Helper cell aborts the downstream star aggregate ─────────────────────

    @Test fun poisonedStarCellPoisonsAMaxValueAggregateOperation() =
        agree(model("../Best", "MaxValue(Pricing* / Helper)"),
            "row-2 Price 5.5 is min-frac-invalid → Helper row 2 poisons → MaxValue over Helper* poisons",
            mapOf(
                "$HELPER@/Shop[1]/Pricing[1]/Helper" to "VALUE|10.00",
                "$HELPER@/Shop[1]/Pricing[2]/Helper" to "CLEARED",
                "$BEST@/Shop[1]/Best" to "CLEARED",
            )) { d ->
            d.group("/Shop", 1).field(BEST, "99.99", 1, 1)
            d.pricingRow(1, "10.00").pricingRow(2, "5.5")
        }

    @Test fun poisonedStarCellPoisonsASumAggregateOperation() =
        agree(model("../Best", "Sum(Pricing* / Helper)"),
            "the class covers every value aggregate — Sum poisons the same way",
            mapOf(
                "$HELPER@/Shop[1]/Pricing[1]/Helper" to "VALUE|10.00",
                "$HELPER@/Shop[1]/Pricing[2]/Helper" to "CLEARED",
                "$BEST@/Shop[1]/Best" to "CLEARED",
            )) { d ->
            d.group("/Shop", 1).field(BEST, "99.99", 1, 1)
            d.pricingRow(1, "10.00").pricingRow(2, "5.5")
        }

    @Test fun poisonedStarCellPoisonsAnAggregateInAPrecondition() =
        agree(model("../Flag", "1", precondition = "MaxValue(Pricing* / Helper) == 10.00"),
            "the R2 minimal's shape — the poisoning read happens while evaluating the PRECONDITION",
            mapOf(
                "$HELPER@/Shop[1]/Pricing[1]/Helper" to "VALUE|10.00",
                "$HELPER@/Shop[1]/Pricing[2]/Helper" to "CLEARED",
                "$FLAG@/Shop[1]/Flag" to "CLEARED",
            )) { d ->
            d.group("/Shop", 1).field(FLAG, "9", 1, 1)
            d.pricingRow(1, "10.00").pricingRow(2, "5.5")
        }

    @Test fun poisonedStarCellPoisonsAPerRowAggregatePrecondition() {
        // The R2 minimal verbatim: the dependent is PER-ROW — `RowFlag = if MaxValue(Pricing*/Helper) == [Helper]
        // → 1` declared in the Pricing group. Every row's precondition reads the whole Helper star, so the
        // poisoned row-2 cell poisons EVERY row's dependent (row 2 additionally reads its own poisoned [Helper]).
        val withHelper = RuntimeLaws.withComputation(base, "/Shop/Pricing", "HelperComp", "../Helper", "[Price]")
        val m = RuntimeLaws.withTieredComputation(withHelper, "/Shop/Pricing", "RowFlagComp", "../RowFlag", null,
            listOf(RuntimeLaws.CompAlt("MaxValue(../Pricing* / Helper) == [Helper]", "1")))
        agree(m, "a per-row dependent's aggregate precondition reads the poisoned star cell → every row poisons",
            mapOf(
                "$HELPER@/Shop[1]/Pricing[1]/Helper" to "VALUE|10.00",
                "$HELPER@/Shop[1]/Pricing[2]/Helper" to "CLEARED",
                "$ROWFLAG@/Shop[1]/Pricing[1]/RowFlag" to "CLEARED",
                "$ROWFLAG@/Shop[1]/Pricing[2]/RowFlag" to "CLEARED",
            )) { d ->
            d.group("/Shop", 1)
            d.pricingRow(1, "10.00").pricingRow(2, "5.5")
            d.field(ROWFLAG, "9", 1, 1, 1).field(ROWFLAG, "9", 1, 2, 1)
        }
    }

    @Test fun poisonedStarCellPoisonsAParentDeclaredPerRowAggregatePrecondition() {
        // The R2 corpus minimal's exact declaration shape: BOTH computations are declared in the PARENT group
        // (the IF89 cross-scope form) — the cascade targets INTO the repeatable child (`../Pricing/Helper`,
        // operand `[Pricing/Price]`) and the dependent's precondition aggregates the star while comparing the
        // row cell (`MaxValue(Pricing* / Helper) == [Pricing/Helper]`).
        val withHelper = RuntimeLaws.withComputation(base, "/Shop", "HelperComp", "../Pricing/Helper", "[Pricing/Price]")
        val m = RuntimeLaws.withTieredComputation(withHelper, "/Shop", "RowFlagComp", "../Pricing/RowFlag", null,
            listOf(RuntimeLaws.CompAlt("MaxValue(Pricing* / Helper) == [Pricing/Helper]", "1")))
        agree(m, "parent-declared per-row cascade + aggregate precondition — the poisoned row-2 cell poisons every row",
            mapOf(
                "$HELPER@/Shop[1]/Pricing[1]/Helper" to "VALUE|10.00",
                "$HELPER@/Shop[1]/Pricing[2]/Helper" to "CLEARED",
                "$ROWFLAG@/Shop[1]/Pricing[1]/RowFlag" to "CLEARED",
                "$ROWFLAG@/Shop[1]/Pricing[2]/RowFlag" to "CLEARED",
            )) { d ->
            d.group("/Shop", 1)
            d.pricingRow(1, "10.00").pricingRow(2, "5.5")
            d.field(ROWFLAG, "9", 1, 1, 1).field(ROWFLAG, "9", 1, 2, 1)
        }
    }

    @Test fun sameGroupStarInAPerRowValidationRuleSpansAllRows() {
        // The BINDING probe on the VALIDATION side, stored values only (no computations): a per-row rule on
        // Pricing whose condition aggregates the SAME group's star — `MaxValue(../Pricing* / Helper) == [Helper]`
        // — fires on the max row(s) ONLY if the bare star spans ALL rows (the `$` operator exists precisely
        // because a bare star never correlates to the outer row); it would fire on EVERY row under an
        // own-row collapse. Pins which rows fire, per pointer.
        val kModel = RuntimeLaws.withRule(base, "/Shop/Pricing", "Q", "../Helper", "Q",
            "MaxValue(../Pricing* / Helper) == [Helper]")
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        b.group("/Shop", 1)
        b.group("/Shop/Pricing", 1, 1).field(HELPER, "10.00", 1, 1, 1)
        b.group("/Shop/Pricing", 1, 2).field(HELPER, "3.10", 1, 2, 1)
        b.group("/Shop/Pricing", 1, 3).field(HELPER, "7.00", 1, 3, 1)
        val kernel = RuntimeLaws.messages(kModel, b.kDoc).filter { it.code() == "Q" }.map { it.field() }.sorted()
        val interp = DmInterpreter(def).validateFull(b.iDoc).filter { it.code == "Q" }.map { it.errorPath }.sorted()
        assertEquals(listOf("/Shop[1]/Pricing[1]/Helper"), kernel,
            "the kernel's bare same-group star spans ALL rows → only the max row fires")
        assertEquals(kernel, interp, "kernel vs interpreter — per-row fire set")
    }

    @Test fun ancestorStarFromANestedRowSpansAllAncestorRows() {
        // The cap's OTHER flipped arm: a star over an ANCESTOR group read from a row nested BELOW it
        // (`MaxValue(../../Pricing* / Helper)` in a per-Tier rule) spans ALL Pricing rows — not just the
        // Tier row's own ancestor. The discriminating cell is a Tier under Pricing row 2 whose Rate equals
        // the GLOBAL max Helper (10.00, on Pricing row 1): all-rows fires it, an own-ancestor collapse
        // (max = 3.10) does not.
        val nested = DocumentModelDraft.named("shop").locale("en_US")
            .rootGroup("Shop", Consumer { g: GroupBuilder ->
                g.group("Pricing", Consumer { p ->
                    p.repeatable(99)
                        .numberField("Helper", Consumer { it.fractionDigits(2, 2) })
                        .group("Tier", Consumer { t ->
                            t.repeatable(99).numberField("Rate", Consumer { it.fractionDigits(2, 2) })
                        })
                })
            }).build().toJson()
        val RATE = "/Shop/Pricing/Tier/Rate"
        val kModel = RuntimeLaws.withRule(nested, "/Shop/Pricing/Tier", "Q", "../Rate", "Q",
            "[Rate] == MaxValue(../../Pricing* / Helper)")
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        b.group("/Shop", 1)
        b.group("/Shop/Pricing", 1, 1).field(HELPER, "10.00", 1, 1, 1)
        b.group("/Shop/Pricing", 1, 2).field(HELPER, "3.10", 1, 2, 1)
        b.group("/Shop/Pricing/Tier", 1, 1, 1).field(RATE, "3.10", 1, 1, 1, 1)
        b.group("/Shop/Pricing/Tier", 1, 2, 1).field(RATE, "10.00", 1, 2, 1, 1)
        val kernel = RuntimeLaws.messages(kModel, b.kDoc).filter { it.code() == "Q" }.map { it.field() }.sorted()
        val interp = DmInterpreter(def).validateFull(b.iDoc).filter { it.code == "Q" }.map { it.errorPath }.sorted()
        assertEquals(listOf("/Shop[1]/Pricing[2]/Tier[1]/Rate"), kernel,
            "the ancestor star spans ALL Pricing rows → only the global-max-matching Tier fires")
        assertEquals(kernel, interp, "kernel vs interpreter — per-row fire set")
    }

    // ── the control: with every Price valid the cascade computes and the aggregate fires ────────────────

    @Test fun validRowsComputeTheAggregate() =
        agree(model("../Best", "MaxValue(Pricing* / Helper)"),
            "control — no poison, the aggregate computes over the cascaded Helper values",
            mapOf(
                "$HELPER@/Shop[1]/Pricing[1]/Helper" to "VALUE|10.00",
                "$HELPER@/Shop[1]/Pricing[2]/Helper" to "VALUE|3.10",
                "$BEST@/Shop[1]/Best" to "VALUE|10.00",
            )) { d ->
            d.group("/Shop", 1).field(BEST, "99.99", 1, 1)
            d.pricingRow(1, "10.00").pricingRow(2, "3.10")
        }
}
