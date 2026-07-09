package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.ComputationFuzz
import io.github.mbackschat.a12.dm.adapter.perf.ModelFuzz
import io.github.mbackschat.a12.dm.adapter.perf.PerfScenario
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseMeta
import io.github.mbackschat.a12.dm.interpreter.conformance.CaseSource
import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.RelevantPointer
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
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
 *
 * The model each case exercises is a **standalone, domain-named DM-JSON file** ([ModelFile]) the case references by
 * `modelRef`; the two comparison cases share one model (`order-qty-low`), which the generator dedupes to a single
 * `models/<name>.json` file. Each model text is pretty-printed here through [CaseJson.writeModel] so exactly the bytes
 * that were tri-verified are the bytes committed (and re-capture is idempotent).
 */
object CorpusScenarios {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val edit = EditSession.read(order)
    private val ord = edit.group("/Order")
    private val items = edit.group("/Order/Items")

    /** Every seed case with the family directory + the standalone model it references; all captured (tri-verified) here. */
    fun all(): List<Family> = listOf(
        orderQtyBelowThreshold(),
        orderQtyWithinThreshold(),
        orderStockPerRow(),
        orderNameSeedEqual(),
        orderPhantomItemRow(),
        orderDeliveryDateInPast(),
        computeCascadeSeed(),
        modelValidationSeed(),
    )

    /** A scenario: the family dir, the tri-verified [case], and the standalone [model] it references by `modelRef`. */
    data class Family(val dir: String, val case: ConformanceCase, val model: ModelFile)

    /** A standalone model file: its domain [name] (→ `models/<name>.json`) and its committed pretty-printed DM-JSON [json]. */
    data class ModelFile(val name: String, val json: String)

    // ---- the shared models (domain-named, pretty-printed to the committed form) ------------------

    /** `[Quantity] < 10` on the root Order — a number comparison; shared by both comparison cases. */
    private val orderQtyLowModel = model("order-qty-low", TypedRules.inject(order, ord.rule {
        it.name("QtyLow").code("QTY_LOW").errorField(ord.num("Quantity").field())
            .`when`(ord.num("Quantity").lt(10)).severity(Severity.ERROR)
            .message(Dsl.en("Quantity is below the minimum order size.")).build()
    }))

    /** `StockQty := [Count] * 10` per Items row — a per-row computation over a repeatable group. */
    private val orderStockComputeModel = model("order-stock-compute", TypedRules.inject(order, items.computation(items.num("StockQty")) {
        it.name("StockComp").calc(items.num("Count").asNumber().times(Dsl.number("10")).expression())
            .message(Dsl.en("x")).build()
    }))

    /** `CustomerName := [ProductCode] + "-X"` on the root Order — a STRING concatenation computation. */
    private val orderNameComputeModel = model("order-name-compute", TypedRules.inject(order, ord.computation(ord.str("CustomerName")) {
        it.name("NameComp").calc(ord.str("ProductCode").asString().plus("-X").expression())
            .message(Dsl.en("x")).build()
    }))

    /** `MinValue(Items* then Count) < 1` on the root Order — an aggregate over the starred Items/Count. */
    private val orderStockLowModel = model("order-stock-low", TypedRules.inject(order, ord.rule {
        it.name("StockLow").code("STOCK_LOW").errorField(items.num("Count").field())
            .`when`(Dsl.minValue(ord.flatten("Items*/Count")).lt(1)).severity(Severity.ERROR)
            .message(Dsl.en("An item has no count.")).build()
    }))

    /** `[DeliveryDate] < Today` on the root Order — a clock comparison. */
    private val orderDeliveryPastModel = model("order-delivery-past", TypedRules.inject(order, ord.rule {
        it.name("DeliveryPast").code("DELIVERY_PAST").errorField(ord.date("DeliveryDate").field())
            .`when`(ord.date("DeliveryDate").before(Dsl.today())).severity(Severity.ERROR)
            .message(Dsl.en("The delivery date is in the past.")).build()
    }))

    // ---- curated cases (typed-authored) ---------------------------------------------------------

    private fun orderQtyBelowThreshold(): Family = family(
        "comparison", orderQtyLowModel,
        CaseMeta("order-qty-below-threshold", PENDING, CaseSource.CURATED, listOf("§7"),
            tags = listOf("comparison"), notes = "a number comparison firing on a below-threshold value"),
        listOf(group("/Order", 1), field("/Order/Quantity", "5", 1, 1)),
        Op.ValidateFull,
    )

    private fun orderQtyWithinThreshold(): Family = family(
        "comparison", orderQtyLowModel,
        CaseMeta("order-qty-within-threshold", PENDING, CaseSource.CURATED, listOf("§7"),
            tags = listOf("comparison"), notes = "nothing fires — an empty expected multiset is a real assertion"),
        listOf(group("/Order", 1), field("/Order/Quantity", "20", 1, 1)),
        Op.ValidateFull,
    )

    /** A per-row computation over a repeatable group — per-row identity. */
    private fun orderStockPerRow(): Family = family(
        "compute", orderStockComputeModel,
        CaseMeta("order-stock-per-row", PENDING, CaseSource.CURATED, listOf("§2"),
            tags = listOf("compute"), notes = "a per-row computation over a repeatable group — per-row identity"),
        listOf(
            group("/Order", 1),
            group("/Order/Items", 1, 1), field("/Order/Items/Sku", "A", 1, 1, 1), field("/Order/Items/Count", "2", 1, 1, 1),
            group("/Order/Items", 1, 2), field("/Order/Items/Sku", "B", 1, 2, 1), field("/Order/Items/Count", "3", 1, 2, 1),
        ),
        Op.Compute,
    )

    /**
     * A STRING computed cell seeded with exactly the value it computes — a kernel NON-change, so
     * `computeExpected` omits it (the kernel's change-only reporting granularity, [§7]); the report-all
     * interpreter must project its seed-equal VALUE away on replay ([kernelComputeGranularity]) instead of
     * false-failing. The guard for the compute-granularity `kernel=absent,interp=VALUE` comparison artifact.
     */
    private fun orderNameSeedEqual(): Family = family(
        "compute", orderNameComputeModel,
        CaseMeta("order-name-seed-equal", PENDING, CaseSource.CURATED, listOf("§2"),
            tags = listOf("compute", "granularity"),
            notes = "a computed STRING equal to its seeded input — a kernel non-change; report-all engines project (§7)"),
        listOf(
            group("/Order", 1),
            field("/Order/ProductCode", "AB", 1, 1),
            field("/Order/CustomerName", "AB-X", 1, 1),
        ),
        Op.Compute,
    )

    /**
     * The instance has NO Items rows, but partial validation marks the **ancestor** `/Order` relevant — its descent
     * makes the star wildcard-relevant, so the empty-identity aggregate FIRES on a phantom `/Order[1]/Items[1]/Count`
     * that does not exist (IF54). (A *concrete* `Items/Count` relevant would instead be IF42-suppressed → no fire.)
     */
    private fun orderPhantomItemRow(): Family = family(
        "partial", orderStockLowModel,
        CaseMeta("order-phantom-item-row", PENDING, CaseSource.CURATED, listOf("§13"),
            tags = listOf("partial", "phantom-row"), notes = "partial validation fires on a relevant row absent from the instance (IF54)"),
        listOf(group("/Order", 1)),
        Op.ValidatePart(listOf(RelevantPointer("/Order", listOf(1)))),
    )

    private fun orderDeliveryDateInPast(): Family = family(
        "clock", orderDeliveryPastModel,
        CaseMeta("order-delivery-date-in-past", PENDING, CaseSource.CURATED, listOf("§8"),
            tags = listOf("clock", "dates"), notes = "a Today comparison, deterministic under the pinned clock"),
        listOf(group("/Order", 1), field("/Order/DeliveryDate", "2020-01-01", 1, 1)),
        Op.ValidateFull,
        clock = "2026-07-06T12:00:00",
    )

    // ---- fuzz-seed cases (pinned from the differential fuzzers) ----------------------------------

    private fun computeCascadeSeed(): Family {
        val scenario = ComputationFuzz.model(3)
        val seed = 7
        return family(
            "fuzz", model("calc-cascade", scenario.kernelModel),
            CaseMeta("compute-cascade-seed", PENDING, CaseSource.FUZZ_SEED, listOf("§2"),
                tags = listOf("compute", "fuzz"), seed = seed, scenario = scenario.name,
                notes = "a pinned computation-cascade fuzz seed"),
            record(scenario, seed), Op.Compute,
        )
    }

    private fun modelValidationSeed(): Family {
        val scenario = ModelFuzz.model(2)
        val seed = 5
        return family(
            "fuzz", model("iteration-groups", scenario.kernelModel),
            CaseMeta("model-validation-seed", PENDING, CaseSource.FUZZ_SEED, listOf("§9"),
                tags = listOf("iteration", "fuzz"), seed = seed, scenario = scenario.name,
                notes = "a pinned model-validation fuzz seed"),
            record(scenario, seed), Op.ValidateFull,
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private const val PENDING = "pending"   // overwritten by CorpusCapture with the pinned kernel version

    /** A standalone model file from raw DM-JSON — identity normalized to the unique [name], pretty-printed to the exact committed form. */
    private fun model(name: String, dmJson: String) = ModelFile(name, CaseJson.writeModel(dmJson, name))

    /** Capture a case against [model] (tri-verified on the committed bytes) and bundle it into a [Family]. */
    private fun family(dir: String, model: ModelFile, meta: CaseMeta, placements: List<Placement>, op: Op, clock: String? = null): Family =
        Family(dir, CorpusCapture.capture(meta, model.name, model.json, placements, op, clock), model)

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
