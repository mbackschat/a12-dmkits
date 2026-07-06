package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.ComputationFuzz
import io.github.mbackschat.a12.dm.adapter.perf.ModelFuzz
import io.github.mbackschat.a12.dm.adapter.perf.PerfScenario
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseMeta
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseSource
import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.RelevantPointer
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity

/**
 * The **initial committed conformance corpus** — own-domain (e-commerce, over the `order` fixture) cases, each
 * tri-verified at build time by [CorpusCapture]. They cover `validateFull` (a firing rule and a clean instance),
 * `validatePart` including a **phantom-row** firing (IF54), `compute` including a repeatable **per-row** case,
 * a **clock** (`Today`) case, and representative **fuzz-seed** cases pinned from the differential fuzzers.
 *
 * Rules/computations are authored through the **typed rulekit surface** (`EditSession` refs + `Dsl`, injected via
 * [TypedRules]) — no hand-written condition/operation strings (the #1 dogfood rule). Migrating the ~105 curated
 * DiffTest scenarios into cases is **drip work** (proposal §6.3), not part of this seed set.
 */
object CorpusScenarios {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val edit = EditSession.read(order)
    private val ord = edit.group("/Order")
    private val items = edit.group("/Order/Items")

    /** Every seed case with the family directory it belongs under; all captured (tri-verified) here. */
    fun all(): List<Family> = listOf(
        Family("comparison", orderQtyBelowThreshold()),
        Family("comparison", orderQtyWithinThreshold()),
        Family("compute", orderStockPerRow()),
        Family("partial", orderPhantomItemRow()),
        Family("clock", orderDeliveryDateInPast()),
        Family("fuzz", computeCascadeSeed()),
        Family("fuzz", modelValidationSeed()),
    )

    data class Family(val dir: String, val case: ConformanceCase)

    // ---- curated cases (typed-authored) ---------------------------------------------------------

    /** `[Quantity] < 10` on the root Order — a number comparison. */
    private fun qtyLowRule(): Rule = ord.rule {
        it.name("QtyLow").code("QTY_LOW").errorField(ord.num("Quantity").field())
            .`when`(ord.num("Quantity").lt(10)).severity(Severity.ERROR)
            .message(Dsl.en("Quantity is below the minimum order size.")).build()
    }

    private fun orderQtyBelowThreshold(): ConformanceCase = CorpusCapture.capture(
        CaseMeta("order-qty-below-threshold", PENDING, CaseSource.CURATED, listOf("§7"),
            tags = listOf("comparison"), notes = "a number comparison firing on a below-threshold value"),
        TypedRules.inject(order, qtyLowRule()),
        listOf(group("/Order", 1), field("/Order/Quantity", "5", 1, 1)),
        Op.ValidateFull,
    )

    private fun orderQtyWithinThreshold(): ConformanceCase = CorpusCapture.capture(
        CaseMeta("order-qty-within-threshold", PENDING, CaseSource.CURATED, listOf("§7"),
            tags = listOf("comparison"), notes = "nothing fires — an empty expected multiset is a real assertion"),
        TypedRules.inject(order, qtyLowRule()),
        listOf(group("/Order", 1), field("/Order/Quantity", "20", 1, 1)),
        Op.ValidateFull,
    )

    /** `StockQty := [Count] * 10` per Items row — a per-row computation over a repeatable group. */
    private fun orderStockPerRow(): ConformanceCase {
        val comp = items.computation(items.num("StockQty")) {
            it.name("StockComp").calc(items.num("Count").asNumber().times(Dsl.number("10")).expression())
                .message(Dsl.en("x")).build()
        }
        return CorpusCapture.capture(
            CaseMeta("order-stock-per-row", PENDING, CaseSource.CURATED, listOf("§2"),
                tags = listOf("compute"), notes = "a per-row computation over a repeatable group — per-row identity"),
            TypedRules.inject(order, comp),
            listOf(
                group("/Order", 1),
                group("/Order/Items", 1, 1), field("/Order/Items/Sku", "A", 1, 1, 1), field("/Order/Items/Count", "2", 1, 1, 1),
                group("/Order/Items", 1, 2), field("/Order/Items/Sku", "B", 1, 2, 1), field("/Order/Items/Count", "3", 1, 2, 1),
            ),
            Op.Compute,
        )
    }

    /**
     * `MinValue(Items* then Count) < 1` on the root Order (error field the starred Items/Count). The instance has
     * NO Items rows, but partial validation marks the **ancestor** `/Order` relevant — its descent makes the star
     * wildcard-relevant, so the empty-identity aggregate FIRES on a phantom `/Order[1]/Items[1]/Count` that does
     * not exist (IF54). (A *concrete* `Items/Count` relevant would instead be IF42-suppressed → no fire.) An
     * aggregate rule, not a per-row negation, so the kernel accepts it at the iteration level.
     */
    private fun orderPhantomItemRow(): ConformanceCase {
        val rule = ord.rule {
            it.name("StockLow").code("STOCK_LOW").errorField(items.num("Count").field())
                .`when`(Dsl.minValue(ord.flatten("Items*/Count")).lt(1)).severity(Severity.ERROR)
                .message(Dsl.en("An item has no count.")).build()
        }
        return CorpusCapture.capture(
            CaseMeta("order-phantom-item-row", PENDING, CaseSource.CURATED, listOf("§13"),
                tags = listOf("partial", "phantom-row"), notes = "partial validation fires on a relevant row absent from the instance (IF54)"),
            TypedRules.inject(order, rule),
            listOf(group("/Order", 1)),
            Op.ValidatePart(listOf(RelevantPointer("/Order", listOf(1)))),
        )
    }

    /** `[DeliveryDate] < Today` on the root Order — a clock comparison, deterministic under the pinned clock. */
    private fun orderDeliveryDateInPast(): ConformanceCase {
        val rule = ord.rule {
            it.name("DeliveryPast").code("DELIVERY_PAST").errorField(ord.date("DeliveryDate").field())
                .`when`(ord.date("DeliveryDate").before(Dsl.today())).severity(Severity.ERROR)
                .message(Dsl.en("The delivery date is in the past.")).build()
        }
        return CorpusCapture.capture(
            CaseMeta("order-delivery-date-in-past", PENDING, CaseSource.CURATED, listOf("§8"),
                tags = listOf("clock", "dates"), notes = "a Today comparison, deterministic under the pinned clock"),
            TypedRules.inject(order, rule),
            listOf(group("/Order", 1), field("/Order/DeliveryDate", "2020-01-01", 1, 1)),
            Op.ValidateFull,
            clock = "2026-07-06T12:00:00",
        )
    }

    // ---- fuzz-seed cases (pinned from the differential fuzzers) ----------------------------------

    private fun computeCascadeSeed(): ConformanceCase {
        val scenario = ComputationFuzz.model(3)
        val seed = 7
        return CorpusCapture.capture(
            CaseMeta("compute-cascade-seed", PENDING, CaseSource.FUZZ_SEED, listOf("§2"),
                tags = listOf("compute", "fuzz"), seed = seed, scenario = scenario.name,
                notes = "a pinned computation-cascade fuzz seed"),
            scenario.kernelModel, record(scenario, seed), Op.Compute,
        )
    }

    private fun modelValidationSeed(): ConformanceCase {
        val scenario = ModelFuzz.model(2)
        val seed = 5
        return CorpusCapture.capture(
            CaseMeta("model-validation-seed", PENDING, CaseSource.FUZZ_SEED, listOf("§9"),
                tags = listOf("iteration", "fuzz"), seed = seed, scenario = scenario.name,
                notes = "a pinned model-validation fuzz seed"),
            scenario.kernelModel, record(scenario, seed), Op.ValidateFull,
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private const val PENDING = "pending"   // overwritten by CorpusCapture with the pinned kernel version

    private fun group(path: String, vararg reps: Int) = Placement(PlacementKind.GROUP, path, reps.toList())
    private fun field(path: String, value: String, vararg reps: Int) = Placement(PlacementKind.FIELD, path, reps.toList(), value)

    /** Record a fuzz [scenario]'s generated placements for [seed] (mirrors the fuzz nets' `instance(sink, 0, seed)`). */
    private fun record(scenario: PerfScenario, seed: Int): List<Placement> {
        val out = mutableListOf<Placement>()
        scenario.instance(object : PlacementSink {
            override fun group(path: String, vararg reps: Int) { out += Placement(PlacementKind.GROUP, path, reps.toList()) }
            override fun field(path: String, value: String, vararg reps: Int) { out += Placement(PlacementKind.FIELD, path, reps.toList(), value) }
            override fun emptyField(path: String, vararg reps: Int) { out += Placement(PlacementKind.EMPTY, path, reps.toList()) }
        }, 0, seed)
        return out
    }
}
