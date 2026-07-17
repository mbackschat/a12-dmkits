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
        strEmptyEqSuppressed(),
        strEmptyLengthLtOmission(),
        strEmptyLengthGeValue(),
        numUnsignedEmptyNeNegOneValue(),
        numSignedEmptyNeNegOneOmission(),
        numUnsignedEmptyNeOneOmission(),
        numSignedFilledZeroNeNegOneValue(),
        strLengthLtEmptyRowClean(),
        numSignedNeNegOneEmptyRowClean(),
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

    // ---- F6: the operator-sensitive empty/polarity control models (one rule each, so each case's
    //          expected multiset is exactly that rule's firing — a shared multi-rule model would let
    //          the other rules fire into a case's expectation and muddy the pairing, SPEC §6). --------

    /** `[ProductCode] == "ABC"` on the root Order — a DIRECT string equality; an empty string is NOT evaluated (§2). */
    private val orderCodeEqualsModel = model("order-code-equals", TypedRules.inject(order, ord.rule {
        it.name("CodeEquals").code("CODE_EQ").errorField(ord.str("ProductCode").field())
            .`when`(ord.str("ProductCode").eq("ABC")).severity(Severity.ERROR)
            .message(Dsl.en("The product code must be ABC.")).build()
    }))

    /** `Length([ProductCode]) < 5` on the root Order — the string family's empty-as-0 backdoor: Length of an
     *  empty string is a fillable 0 (grow-only), so it FIRES where the direct comparison is suppressed (IF73). */
    private val orderCodeLengthLtModel = model("order-code-length-lt", TypedRules.inject(order, ord.rule {
        it.name("CodeLenLt").code("CODE_LEN_LT").errorField(ord.str("ProductCode").field())
            .`when`(Dsl.length(ord.str("ProductCode")).lt(5)).severity(Severity.ERROR)
            .message(Dsl.en("The product code is too short.")).build()
    }))

    /** `Length([ProductCode]) >= 0` on the root Order — always true; the grow-only VALUE witness (filling can
     *  only lengthen, so a `>=` can never be cleared → VALUE, not the OMISSION a symmetric leaf would give). */
    private val orderCodeLengthGeModel = model("order-code-length-ge", TypedRules.inject(order, ord.rule {
        it.name("CodeLenGe").code("CODE_LEN_GE").errorField(ord.str("ProductCode").field())
            .`when`(Dsl.length(ord.str("ProductCode")).ge(0)).severity(Severity.ERROR)
            .message(Dsl.en("The product code length is non-negative.")).build()
    }))

    /** `[StockOnHand] != -1` on the root Order — StockOnHand is UNSIGNED (`positivesOnly`); it can't shrink to -1. */
    private val orderStockNeNegOneModel = model("order-stock-ne-neg-one", TypedRules.inject(order, ord.rule {
        it.name("StockNeNegOne").code("STOCK_NE_NEG1").errorField(ord.num("StockOnHand").field())
            .`when`(ord.num("StockOnHand").ne(-1)).severity(Severity.ERROR)
            .message(Dsl.en("Stock on hand must not equal minus one.")).build()
    }))

    /** `[Quantity] != -1` on the root Order — Quantity is SIGNED (default); it CAN shrink to -1. */
    private val orderQtyNeNegOneModel = model("order-qty-ne-neg-one", TypedRules.inject(order, ord.rule {
        it.name("QtyNeNegOne").code("QTY_NE_NEG1").errorField(ord.num("Quantity").field())
            .`when`(ord.num("Quantity").ne(-1)).severity(Severity.ERROR)
            .message(Dsl.en("Quantity must not equal minus one.")).build()
    }))

    /** `[StockOnHand] != 1` on the root Order — UNSIGNED, but the literal is reachable by GROWING from 0. */
    private val orderStockNeOneModel = model("order-stock-ne-one", TypedRules.inject(order, ord.rule {
        it.name("StockNeOne").code("STOCK_NE_1").errorField(ord.num("StockOnHand").field())
            .`when`(ord.num("StockOnHand").ne(1)).severity(Severity.ERROR)
            .message(Dsl.en("Stock on hand must not equal one.")).build()
    }))

    // ---- curated cases (typed-authored) ---------------------------------------------------------

    private fun orderQtyBelowThreshold(): Family = family(
        "comparison", orderQtyLowModel,
        CaseMeta("order-qty-below-threshold", PENDING, CaseSource.CURATED, listOf("§5"),
            tags = listOf("comparison"), notes = "a number comparison firing on a below-threshold value"),
        listOf(group("/Order", 1), field("/Order/Quantity", "5", 1, 1)),
        Op.ValidateFull,
    )

    private fun orderQtyWithinThreshold(): Family = family(
        "comparison", orderQtyLowModel,
        CaseMeta("order-qty-within-threshold", PENDING, CaseSource.CURATED, listOf("§5"),
            tags = listOf("comparison"), notes = "nothing fires — an empty expected multiset is a real assertion"),
        listOf(group("/Order", 1), field("/Order/Quantity", "20", 1, 1)),
        Op.ValidateFull,
    )

    /** A per-row computation over a repeatable group — per-row identity. */
    private fun orderStockPerRow(): Family = family(
        "compute", orderStockComputeModel,
        CaseMeta("order-stock-per-row", PENDING, CaseSource.CURATED, listOf("§9", "§11"),
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
     * `computeExpected` omits it (the kernel's change-only reporting granularity, CONFORMANCE-CORPUS-SPEC §7); the report-all
     * interpreter must project its seed-equal VALUE away on replay ([kernelComputeGranularity]) instead of
     * false-failing. The guard for the compute-granularity `kernel=absent,interp=VALUE` comparison artifact.
     */
    private fun orderNameSeedEqual(): Family = family(
        "compute", orderNameComputeModel,
        CaseMeta("order-name-seed-equal", PENDING, CaseSource.CURATED, listOf("§11"),
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
        CaseMeta("order-phantom-item-row", PENDING, CaseSource.CURATED, listOf("§12"),
            tags = listOf("partial", "phantom-row"), notes = "partial validation fires on a relevant row absent from the instance (IF54)"),
        listOf(group("/Order", 1)),
        Op.ValidatePart(listOf(RelevantPointer("/Order", listOf(1)))),
    )

    private fun orderDeliveryDateInPast(): Family = family(
        "clock", orderDeliveryPastModel,
        CaseMeta("order-delivery-date-in-past", PENDING, CaseSource.CURATED, listOf("§6"),
            tags = listOf("clock", "dates"), notes = "a Today comparison, deterministic under the pinned clock"),
        listOf(group("/Order", 1), field("/Order/DeliveryDate", "2020-01-01", 1, 1)),
        Op.ValidateFull,
        clock = "2026-07-06T12:00:00",
    )

    // ---- F6: operator-sensitive empty/polarity control cases (the a12-kernel-lean feedback's
    //          preserved message list, §15f). Every empty-value case sits in a CONTENT-BEARING row
    //          (a filled CustomerName sibling), because an entirely-empty row is not evaluated and its
    //          eligibility would otherwise confound the operator behavior; the two `*-empty-row-clean`
    //          controls isolate that row-eligibility precondition (all-empty row → nothing fires). ----

    /** 1 — an empty ProductCode compared DIRECTLY (`== "ABC"`) is suppressed; the operator-sensitivity pair of
     *  the Length case, on the SAME empty value. */
    private fun strEmptyEqSuppressed(): Family = family(
        F6, orderCodeEqualsModel,
        CaseMeta("str-empty-eq-suppressed", PENDING, CaseSource.CURATED, listOf("§2", "§7"),
            tags = listOf("f6", "empty", "strings", "f6-pair-operator"),
            notes = "a direct == over an empty String is not evaluated; pairs with str-empty-length-lt-omission on the varied axis = the operator applied to the same empty ProductCode"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), empty(PRODUCT_CODE, 1, 1)),
        Op.ValidateFull,
    )

    /** 2 — `Length([ProductCode]) < 5` over the same empty ProductCode FIRES as OMISSION (empty-as-0 backdoor). */
    private fun strEmptyLengthLtOmission(): Family = family(
        F6, orderCodeLengthLtModel,
        CaseMeta("str-empty-length-lt-omission", PENDING, CaseSource.CURATED, listOf("§2", "§7", "§12"),
            tags = listOf("f6", "empty", "strings", "polarity", "f6-pair-operator", "f6-pair-direction", "f6-pair-row-eligibility"),
            notes = "Length of an empty String is a fillable 0 (0 < 5 → OMISSION); the operator pair of str-empty-eq-suppressed and the direction pair of str-empty-length-ge-value (varied axis = the comparison direction)"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), empty(PRODUCT_CODE, 1, 1)),
        Op.ValidateFull,
    )

    /** 3 — `Length([ProductCode]) >= 0` FIRES as VALUE — Length is grow-only, so a `>=` can't be filled to clear. */
    private fun strEmptyLengthGeValue(): Family = family(
        F6, orderCodeLengthGeModel,
        CaseMeta("str-empty-length-ge-value", PENDING, CaseSource.CURATED, listOf("§2", "§7", "§12"),
            tags = listOf("f6", "empty", "strings", "polarity", "f6-pair-direction"),
            notes = "Length is grow-only, so an always-true `>= 0` fires as VALUE (filling only lengthens); the comparison-direction pair of str-empty-length-lt-omission"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), empty(PRODUCT_CODE, 1, 1)),
        Op.ValidateFull,
    )

    /** 4 — an empty UNSIGNED StockOnHand reads as 0; `0 != -1` FIRES as VALUE (can't shrink to -1). */
    private fun numUnsignedEmptyNeNegOneValue(): Family = family(
        F6, orderStockNeNegOneModel,
        CaseMeta("num-unsigned-empty-ne-neg1-value", PENDING, CaseSource.CURATED, listOf("§2", "§5", "§12"),
            tags = listOf("f6", "empty", "numbers", "polarity", "f6-pair-signedness", "f6-pair-literal-direction"),
            notes = "an unsigned empty number is 0 and can't shrink to -1, so `!= -1` fires as VALUE; the signedness pair of num-signed-empty-ne-neg1-omission and the literal-direction pair of num-unsigned-empty-ne-1-omission"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), empty(STOCK, 1, 1)),
        Op.ValidateFull,
    )

    /** 5 — an empty SIGNED Quantity reads as 0; `0 != -1` FIRES as OMISSION (can shrink to -1). */
    private fun numSignedEmptyNeNegOneOmission(): Family = family(
        F6, orderQtyNeNegOneModel,
        CaseMeta("num-signed-empty-ne-neg1-omission", PENDING, CaseSource.CURATED, listOf("§2", "§5", "§12"),
            tags = listOf("f6", "empty", "numbers", "polarity", "f6-pair-signedness", "f6-pair-filled-state", "f6-pair-row-eligibility"),
            notes = "a signed empty number is 0 and CAN shrink to -1, so `!= -1` fires as OMISSION; the signedness pair of num-unsigned-empty-ne-neg1-value and the filled-state pair of num-signed-filled-zero-ne-neg1-value"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), empty(QUANTITY, 1, 1)),
        Op.ValidateFull,
    )

    /** 6 — an empty UNSIGNED StockOnHand reads as 0; `0 != 1` FIRES as OMISSION — the literal is reachable by growing. */
    private fun numUnsignedEmptyNeOneOmission(): Family = family(
        F6, orderStockNeOneModel,
        CaseMeta("num-unsigned-empty-ne-1-omission", PENDING, CaseSource.CURATED, listOf("§2", "§5", "§12"),
            tags = listOf("f6", "empty", "numbers", "polarity", "f6-pair-literal-direction"),
            notes = "an unsigned empty number is 0 and CAN grow to 1, so `!= 1` fires as OMISSION; the literal-direction pair of num-unsigned-empty-ne-neg1-value (same field/sign, only the literal moves from below to above 0)"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), empty(STOCK, 1, 1)),
        Op.ValidateFull,
    )

    /** 7 — a FILLED zero Quantity (`0`) `!= -1` FIRES as VALUE — a present value is a value error, not an omission. */
    private fun numSignedFilledZeroNeNegOneValue(): Family = family(
        F6, orderQtyNeNegOneModel,
        CaseMeta("num-signed-filled-zero-ne-neg1-value", PENDING, CaseSource.CURATED, listOf("§5", "§12"),
            tags = listOf("f6", "filled", "numbers", "polarity", "f6-pair-filled-state"),
            notes = "a filled 0 (not empty) fires `!= -1` as VALUE; the filled-state pair of num-signed-empty-ne-neg1-omission (same signed rule, only empty→filled differs, flipping OMISSION→VALUE)"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), field(QUANTITY, "0", 1, 1)),
        Op.ValidateFull,
    )

    /** 8 — control: the Length `< 5` rule over an ALL-EMPTY Order row fires NOTHING (an all-empty row is not
     *  evaluated), isolating row eligibility from the operator firing of case 2. */
    private fun strLengthLtEmptyRowClean(): Family = family(
        F6, orderCodeLengthLtModel,
        CaseMeta("str-length-lt-empty-row-clean", PENDING, CaseSource.CURATED, listOf("§2", "§7"),
            tags = listOf("f6", "control", "empty-row", "strings", "f6-pair-row-eligibility"),
            notes = "an ALL-EMPTY row (no sibling value) is not evaluated, so nothing fires; the row-eligibility control for str-empty-length-lt-omission (varied axis = the content-bearing sibling)"),
        listOf(group("/Order", 1), empty(PRODUCT_CODE, 1, 1)),
        Op.ValidateFull,
    )

    /** 9 — control: the signed `!= -1` rule over an ALL-EMPTY Order row fires NOTHING, isolating row eligibility
     *  from the empty-as-0 firing of case 5. */
    private fun numSignedNeNegOneEmptyRowClean(): Family = family(
        F6, orderQtyNeNegOneModel,
        CaseMeta("num-signed-ne-neg1-empty-row-clean", PENDING, CaseSource.CURATED, listOf("§2", "§5"),
            tags = listOf("f6", "control", "empty-row", "numbers", "f6-pair-row-eligibility"),
            notes = "an ALL-EMPTY row is not evaluated, so the empty-as-0 substitution never occurs and nothing fires; the row-eligibility control for num-signed-empty-ne-neg1-omission"),
        listOf(group("/Order", 1), empty(QUANTITY, 1, 1)),
        Op.ValidateFull,
    )

    // ---- fuzz-seed cases (pinned from the differential fuzzers) ----------------------------------

    private fun computeCascadeSeed(): Family {
        val scenario = ComputationFuzz.model(3)
        val seed = 7
        return family(
            "fuzz", model("calc-cascade", scenario.kernelModel),
            CaseMeta("compute-cascade-seed", PENDING, CaseSource.FUZZ_SEED, listOf("§11"),
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

    /** The F6 family directory (operator-sensitive empty/polarity controls) + the fields those cases place. */
    private const val F6 = "empty-polarity"
    private const val CUSTOMER = "/Order/CustomerName"   // the filled sibling that makes a row content-bearing
    private const val PRODUCT_CODE = "/Order/ProductCode"
    private const val STOCK = "/Order/StockOnHand"       // UNSIGNED number (positivesOnly)
    private const val QUANTITY = "/Order/Quantity"       // SIGNED number (default)

    /** A standalone model file from raw DM-JSON — identity normalized to the unique [name], pretty-printed to the exact committed form. */
    private fun model(name: String, dmJson: String) = ModelFile(name, CaseJson.writeModel(dmJson, name))

    /** Capture a case against [model] (tri-verified on the committed bytes) and bundle it into a [Family]. */
    private fun family(dir: String, model: ModelFile, meta: CaseMeta, placements: List<Placement>, op: Op, clock: String? = null): Family =
        Family(dir, CorpusCapture.capture(meta, model.name, model.json, placements, op, clock), model)

    private fun group(path: String, vararg reps: Int) = Placement(PlacementKind.GROUP, path, reps.toList())
    private fun field(path: String, value: String, vararg reps: Int) = Placement(PlacementKind.FIELD, path, reps.toList(), value)

    /** A present-but-valueless field cell (SPEC §4 EMPTY) — an instantiated-empty cell, distinct from an absent one. */
    private fun empty(path: String, vararg reps: Int) = Placement(PlacementKind.EMPTY, path, reps.toList())

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
