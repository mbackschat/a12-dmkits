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

    // F7 bases: the cart fixture (a repeatable Lines group — CurrentRepetition) and the staffing fixture
    // (two sibling Department-indexed groups — parallel iteration), both own-domain like the order base.
    private val cart = RuntimeLaws.model("models/fuzz.dm.json")
    private val cartEdit = EditSession.read(cart)
    private val cartRoot = cartEdit.group("/Cart")
    private val lines = cartEdit.group("/Cart/Lines")

    private val staffing = RuntimeLaws.model("models/staffing.dm.json")
    private val staffEdit = EditSession.read(staffing)
    private val staff = staffEdit.group("/Staffing")
    private val demand = staffEdit.group("/Staffing/Demand")
    private val supply = staffEdit.group("/Staffing/Supply")

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
        havingSumKeepsOnlyMatchingRows(),
        havingSumExcludedRowsClean(),
        havingAllFilteredOutSumsZero(),
        havingQuantifierFiredOmission(),
        filterCorrelatedDuplicatesFire(),
        filterCorrelatedAllDistinctClean(),
        filterSingleRowSelfExcludedClean(),
        filterDominantRowFires(),
        filterSelfMatchFiresEveryRow(),
        currentRepetitionFiresBeyondFirstRow(),
        currentRepetitionSingleRowClean(),
        currentRepetitionPredecessorMaxFires(),
        currentRepetitionOrderedRowsClean(),
        parallelIterationUnmatchedSidesFire(),
        parallelIterationAllMatchedClean(),
        parallelIterationEmptySideReadsZero(),
        parallelIterationInvalidIndexSuppresses(),
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

    /** `Length(ProductCode) < 5` on the root Order — the string family's empty-as-0 backdoor: Length of an
     *  empty string is a fillable 0 (grow-only), so it FIRES where the direct comparison is suppressed (IF73). */
    private val orderCodeLengthLtModel = model("order-code-length-lt", TypedRules.inject(order, ord.rule {
        it.name("CodeLenLt").code("CODE_LEN_LT").errorField(ord.str("ProductCode").field())
            .`when`(Dsl.length(ord.str("ProductCode")).lt(5)).severity(Severity.ERROR)
            .message(Dsl.en("The product code is too short.")).build()
    }))

    /** `Length(ProductCode) >= 0` on the root Order — always true; the grow-only VALUE witness (filling can
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

    // ---- F7: the iteration/correlation constructs — Having, the $ filter, CurrentRepetition, and
    //          parallel iteration (KERNEL-SEMANTICS §9). One rule per model (the F6 pairing discipline);
    //          each family pins a firing case, a clean case, and a binding/scope edge. -------------------

    /** `Sum(Items* then Count Having [Items/UnitWeight] == 1) > 5` — a filtered aggregate; star-only, so it
     *  fires ONCE at the pinned first-row anchor (IF127). */
    private val orderLightSumModel = model("order-light-sum", TypedRules.inject(order, ord.rule {
        it.name("LightSum").code("LIGHT_SUM").errorField(items.num("Count").field())
            .`when`(Dsl.sum(ord.flatten("Items*/Count").having(items.num("UnitWeight").eq(1))).gt(5))
            .severity(Severity.ERROR)
            .message(Dsl.en("The light items' total count is too high.")).build()
    }))

    /** `Sum(Items* then Count Having [Items/UnitWeight] == 1) == 0` — fires exactly when the filter keeps
     *  no row (the empty keep-set folds to the Sum identity 0). */
    private val orderNoLightItemsModel = model("order-no-light-items", TypedRules.inject(order, ord.rule {
        it.name("NoLight").code("NO_LIGHT").errorField(items.num("Count").field())
            .`when`(Dsl.sum(ord.flatten("Items*/Count").having(items.num("UnitWeight").eq(1))).eq(0))
            .severity(Severity.ERROR)
            .message(Dsl.en("The order carries no light item.")).build()
    }))

    /** `AtLeastOneFieldFilled(Items* then Count Having [Items/UnitWeight] == 1)` — the fill quantifier over a
     *  filtered star; a fired result is UNCONDITIONALLY OMISSION (the Having escalation, IF101). */
    private val orderLightPresentModel = model("order-light-present", TypedRules.inject(order, ord.rule {
        it.name("LightPresent").code("LIGHT_PRESENT").errorField(items.num("Count").field())
            .`when`(Dsl.atLeastOneFieldFilled(ord.flatten("Items*/Count").having(items.num("UnitWeight").eq(1))))
            .severity(Severity.ERROR)
            .message(Dsl.en("A light item is on the order.")).build()
    }))

    /** The `$`-correlated duplicate detector — "another Items row shares this row's Count": a per-row rule whose
     *  filter self-excludes via `CurrentRepetition(Items) != CurrentRepetition($Items)` and joins on the value. */
    private val orderDuplicateCountModel = model("order-duplicate-count", TypedRules.inject(order, ord.rule {
        it.name("DupCount").code("DUP_COUNT").errorField(items.num("Count").field())
            .`when`(items.num("Count").filled().and(Dsl.atLeastOneFieldFilled(
                ord.flatten("Items*/Count").having(
                    Dsl.currentRepetition(ord.group("Items")).ne(Dsl.currentRepetition(ord.group("Items"), true))
                        .and(items.num("Count").eq(items.num("Count").outer()))))))
            .severity(Severity.ERROR)
            .message(Dsl.en("Another item line carries the same count.")).build()
    }))

    /** The same `$` value join WITHOUT the self-exclusion — the filter's bare side iterates ALL rows, the
     *  outer row included, so every filled row matches itself. */
    private val orderSelfMatchCountModel = model("order-self-match-count", TypedRules.inject(order, ord.rule {
        it.name("SelfMatch").code("SELF_MATCH").errorField(items.num("Count").field())
            .`when`(items.num("Count").filled().and(Dsl.atLeastOneFieldFilled(
                ord.flatten("Items*/Count").having(items.num("Count").eq(items.num("Count").outer())))))
            .severity(Severity.ERROR)
            .message(Dsl.en("An item line carries this count.")).build()
    }))

    /** The `$`-correlated DOMINANT-row detector — "this row's Count exceeds ALL OTHER rows' combined": a per-row
     *  rule whose VALUE aggregate consumes the `$`-self-excluded keep-set (the quantifier twin is DupCount). */
    private val orderDominantCountModel = model("order-dominant-count", TypedRules.inject(order, ord.rule {
        it.name("DominantCount").code("DOMINANT_COUNT").errorField(items.num("Count").field())
            .`when`(items.num("Count").filled().and(items.num("Count").asNumber().gt(Dsl.sum(
                ord.flatten("Items*/Count").having(
                    Dsl.currentRepetition(ord.group("Items")).ne(Dsl.currentRepetition(ord.group("Items"), true)))))))
            .severity(Severity.ERROR)
            .message(Dsl.en("One item line outweighs all the others combined.")).build()
    }))

    /** `FieldFilled(Lines/Qty) And CurrentRepetition(Lines) > 1` — the structural row index, per Lines row. */
    private val cartBeyondFirstLineModel = model("cart-beyond-first-line", TypedRules.inject(cart, cartRoot.rule {
        it.name("BeyondFirst").code("BEYOND_FIRST").errorField(lines.num("Qty").field())
            .`when`(lines.num("Qty").filled().and(Dsl.currentRepetition(cartRoot.group("Lines")).gt(1)))
            .severity(Severity.ERROR)
            .message(Dsl.en("A cart line beyond the first needs a review.")).build()
    }))

    /** The BA "all predecessors" idiom (own-domain): `MaxValue(Lines* then Qty Having CurrentRepetition(Lines)
     *  < CurrentRepetition($Lines)) >= [Lines/Qty]` — quantities must increase strictly across lines. */
    private val cartQtyOrderModel = model("cart-qty-order", TypedRules.inject(cart, cartRoot.rule {
        it.name("QtyOrder").code("QTY_ORDER").errorField(lines.num("Qty").field())
            .`when`(Dsl.maxValue(cartRoot.flatten("Lines*/Qty").having(
                    Dsl.currentRepetition(cartRoot.group("Lines"))
                        .lt(Dsl.currentRepetition(cartRoot.group("Lines"), true))))
                .ge(lines.num("Qty").asNumber()))
            .severity(Severity.ERROR)
            .message(Dsl.en("Line quantities must increase from line to line.")).build()
    }))

    /** `FieldsNotCollectivelyFilled(Demand/Headcount, Supply/Headcount)` — the index-keyed outer join over the
     *  sibling Department-indexed groups. */
    private val staffingCoveragePairModel = model("staffing-coverage-pair", TypedRules.inject(staffing, staff.rule {
        it.name("CoverPair").code("COVER_PAIR").errorField(demand.num("Headcount").field())
            .`when`(Dsl.fieldsNotCollectivelyFilled(demand.field("Headcount"), supply.field("Headcount")))
            .severity(Severity.ERROR)
            .message(Dsl.en("Demand and supply must be planned together per department.")).build()
    }))

    /** `[Demand/Headcount] > [Supply/Headcount]` — an unguarded per-key compare across the parallel join. */
    private val staffingOverDemandModel = model("staffing-demand-exceeds-supply", TypedRules.inject(staffing, staff.rule {
        it.name("OverDemand").code("OVER_DEMAND").errorField(demand.num("Headcount").field())
            .`when`(demand.num("Headcount").gt(supply.num("Headcount")))
            .severity(Severity.ERROR)
            .message(Dsl.en("A department demands more headcount than its supply covers.")).build()
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

    /** 2 — `Length(ProductCode) < 5` over the same empty ProductCode FIRES as OMISSION (empty-as-0 backdoor). */
    private fun strEmptyLengthLtOmission(): Family = family(
        F6, orderCodeLengthLtModel,
        CaseMeta("str-empty-length-lt-omission", PENDING, CaseSource.CURATED, listOf("§2", "§7", "§12"),
            tags = listOf("f6", "empty", "strings", "polarity", "f6-pair-operator", "f6-pair-direction", "f6-pair-row-eligibility"),
            notes = "Length of an empty String is a fillable 0 (0 < 5 → OMISSION); the operator pair of str-empty-eq-suppressed and the direction pair of str-empty-length-ge-value (varied axis = the comparison direction)"),
        listOf(group("/Order", 1), field(CUSTOMER, "Acme", 1, 1), empty(PRODUCT_CODE, 1, 1)),
        Op.ValidateFull,
    )

    /** 3 — `Length(ProductCode) >= 0` FIRES as VALUE — Length is grow-only, so a `>=` can't be filled to clear. */
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

    // ---- F7: `Having` — the per-row filter on a starred aggregate (§9) ---------------------------

    private fun havingSumKeepsOnlyMatchingRows(): Family = family(
        HAVING, orderLightSumModel,
        CaseMeta("having-sum-keeps-only-matching-rows", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("having", "aggregates"),
            notes = "the filter keeps only the weight-1 rows (3+4=7 > 5) — the excluded 10 never enters the sum; star-only, so ONE fire at the pinned first-row anchor (IF127)"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "3", "1.00") + itemRow(2, "B", "10", "2.00") + itemRow(3, "C", "4", "1.00"),
        Op.ValidateFull,
    )

    private fun havingSumExcludedRowsClean(): Family = family(
        HAVING, orderLightSumModel,
        CaseMeta("having-sum-excluded-rows-clean", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("having", "aggregates"),
            notes = "the kept rows sum to 4 (not > 5) while the UNFILTERED sum 14 would fire — the clean case is what distinguishes a filter-honoring engine"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "2", "1.00") + itemRow(2, "B", "10", "2.00") + itemRow(3, "C", "2", "1.00"),
        Op.ValidateFull,
    )

    private fun havingAllFilteredOutSumsZero(): Family = family(
        HAVING, orderNoLightItemsModel,
        CaseMeta("having-all-filtered-out-sums-zero", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("having", "aggregates", "empty"),
            notes = "the filter keeps NO row — the empty keep-set folds to the Sum identity 0, so `== 0` fires"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "3", "2.00") + itemRow(2, "B", "10", "2.00"),
        Op.ValidateFull,
    )

    private fun havingQuantifierFiredOmission(): Family = family(
        HAVING, orderLightPresentModel,
        CaseMeta("having-quantifier-fired-omission", PENDING, CaseSource.CURATED, listOf("§9", "§12"),
            tags = listOf("having", "polarity"),
            notes = "a fired fill quantifier over a Having-filtered star is UNCONDITIONALLY OMISSION (the escalation, IF101) — even over a fully-kept FILLED row, where an unfiltered quantifier would type VALUE"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "5", "1.00"),
        Op.ValidateFull,
    )

    // ---- F7: the `$` filter correlation — the outer-row join inside `Having` (§9) ----------------

    private fun filterCorrelatedDuplicatesFire(): Family = family(
        FILTER, orderDuplicateCountModel,
        CaseMeta("filter-correlated-duplicates-fire", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("filter", "correlation", "having"),
            notes = "[\$Count] binds each filter evaluation to the OUTER iterating row — counts 5,5,9 fire exactly on the two duplicate rows"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "5") + itemRow(2, "B", "5") + itemRow(3, "C", "9"),
        Op.ValidateFull,
    )

    private fun filterCorrelatedAllDistinctClean(): Family = family(
        FILTER, orderDuplicateCountModel,
        CaseMeta("filter-correlated-all-distinct-clean", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("filter", "correlation", "having"),
            notes = "all counts distinct — the self-excluded value join keeps no row for any outer row, nothing fires"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "5") + itemRow(2, "B", "6") + itemRow(3, "C", "9"),
        Op.ValidateFull,
    )

    private fun filterSingleRowSelfExcludedClean(): Family = family(
        FILTER, orderDuplicateCountModel,
        CaseMeta("filter-single-row-self-excluded-clean", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("filter", "correlation", "having"),
            notes = "a single row: `CurrentRepetition(Items) != CurrentRepetition(\$Items)` excludes the only row, so the correlated quantifier can never fire"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "5"),
        Op.ValidateFull,
    )

    private fun filterDominantRowFires(): Family = family(
        FILTER, orderDominantCountModel,
        CaseMeta("filter-dominant-row-fires", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("filter", "correlation", "having", "aggregates"),
            notes = "a VALUE aggregate over the \$-self-excluded keep-set: counts 3,9,2 fire exactly on row 2 (9 > the OTHERS' sum 5) — an engine summing ALL rows (no exclusion) or only the own row could never fire it"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "3") + itemRow(2, "B", "9") + itemRow(3, "C", "2"),
        Op.ValidateFull,
    )

    private fun filterSelfMatchFiresEveryRow(): Family = family(
        FILTER, orderSelfMatchCountModel,
        CaseMeta("filter-self-match-fires-every-row", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("filter", "correlation", "having"),
            notes = "WITHOUT the self-exclusion the filter's bare side iterates ALL rows including the outer row itself, so every filled row self-matches and fires — the binding edge the self-excluded twin controls"),
        listOf(group("/Order", 1)) + itemRow(1, "A", "5") + itemRow(2, "B", "6") + itemRow(3, "C", "9"),
        Op.ValidateFull,
    )

    // ---- F7: CurrentRepetition — the structural row index + the predecessors idiom (§9) ----------

    private fun currentRepetitionFiresBeyondFirstRow(): Family = family(
        CURRENT_REP, cartBeyondFirstLineModel,
        CaseMeta("current-repetition-fires-beyond-first-row", PENDING, CaseSource.CURATED, listOf("§9", "§12"),
            tags = listOf("current-repetition", "polarity"),
            notes = "CurrentRepetition is the 1-based structural row index: rows 2,3 fire, row 1 does not — and the fired type is VALUE (no fill can change a row's index)"),
        listOf(group("/Cart", 1)) + lineRow(1, "5") + lineRow(2, "5") + lineRow(3, "5"),
        Op.ValidateFull,
    )

    private fun currentRepetitionSingleRowClean(): Family = family(
        CURRENT_REP, cartBeyondFirstLineModel,
        CaseMeta("current-repetition-single-row-clean", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("current-repetition"),
            notes = "a single row carries index 1 — `CurrentRepetition(Lines) > 1` never fires"),
        listOf(group("/Cart", 1)) + lineRow(1, "5"),
        Op.ValidateFull,
    )

    private fun currentRepetitionPredecessorMaxFires(): Family = family(
        CURRENT_REP, cartQtyOrderModel,
        CaseMeta("current-repetition-predecessor-max-fires", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("current-repetition", "correlation", "having"),
            notes = "the predecessors idiom `CurrentRepetition(Lines) < CurrentRepetition(\$Lines)`: quantities 5,3,9 fire exactly on row 2 (its predecessor max 5 >= 3); row 3's predecessor max 5 < 9 stays clean"),
        listOf(group("/Cart", 1)) + lineRow(1, "5") + lineRow(2, "3") + lineRow(3, "9"),
        Op.ValidateFull,
    )

    private fun currentRepetitionOrderedRowsClean(): Family = family(
        CURRENT_REP, cartQtyOrderModel,
        CaseMeta("current-repetition-ordered-rows-clean", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("current-repetition", "correlation", "having"),
            notes = "strictly increasing quantities stay clean — including row 1, whose EMPTY predecessor set folds MaxValue to 0 (0 >= 3 is false), not to a fire or a suppression"),
        listOf(group("/Cart", 1)) + lineRow(1, "3") + lineRow(2, "4") + lineRow(3, "5"),
        Op.ValidateFull,
    )

    // ---- F7: parallel iteration — the index-keyed outer join over sibling groups (§9) ------------

    private fun parallelIterationUnmatchedSidesFire(): Family = family(
        PARALLEL, staffingCoveragePairModel,
        CaseMeta("parallel-iteration-unmatched-sides-fire", PENDING, CaseSource.CURATED, listOf("§9", "§12"),
            tags = listOf("parallel-iteration", "polarity"),
            notes = "the join iterates the UNION of the Department index values: Eng (Supply missing) fires at the real Demand row, Ops (Demand missing) fires at the kernel's UNKNOWN sentinel row [-5]; both OMISSION (an unspecified side is fillable)"),
        listOf(group("/Staffing", 1), field("/Staffing/PlanName", "Q3", 1, 1))
            + staffRow("/Staffing/Demand", 1, "Sales", "5") + staffRow("/Staffing/Demand", 2, "Eng", "3")
            + staffRow("/Staffing/Supply", 1, "Sales", "5") + staffRow("/Staffing/Supply", 2, "Ops", "2"),
        Op.ValidateFull,
    )

    private fun parallelIterationAllMatchedClean(): Family = family(
        PARALLEL, staffingCoveragePairModel,
        CaseMeta("parallel-iteration-all-matched-clean", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("parallel-iteration"),
            notes = "every Department value has a filled row on both sides — the outer join finds no unmatched side, nothing fires"),
        listOf(group("/Staffing", 1), field("/Staffing/PlanName", "Q3", 1, 1))
            + staffRow("/Staffing/Demand", 1, "Sales", "5") + staffRow("/Staffing/Demand", 2, "Eng", "3")
            + staffRow("/Staffing/Supply", 1, "Sales", "2") + staffRow("/Staffing/Supply", 2, "Eng", "4"),
        Op.ValidateFull,
    )

    private fun parallelIterationEmptySideReadsZero(): Family = family(
        PARALLEL, staffingOverDemandModel,
        CaseMeta("parallel-iteration-empty-side-reads-zero", PENDING, CaseSource.CURATED, listOf("§9", "§12"),
            tags = listOf("parallel-iteration", "empty", "polarity"),
            notes = "the join is BY KEY, never by row position: Sales 9>5 fires VALUE (all specified); Eng's missing Supply side reads empty-as-0, 3>0 fires OMISSION; Ops 0>2 stays silent"),
        listOf(group("/Staffing", 1), field("/Staffing/PlanName", "Q3", 1, 1))
            + staffRow("/Staffing/Demand", 1, "Sales", "9") + staffRow("/Staffing/Demand", 2, "Eng", "3")
            + staffRow("/Staffing/Supply", 1, "Sales", "5") + staffRow("/Staffing/Supply", 2, "Ops", "2"),
        Op.ValidateFull,
    )

    private fun parallelIterationInvalidIndexSuppresses(): Family = family(
        PARALLEL, staffingCoveragePairModel,
        CaseMeta("parallel-iteration-invalid-index-suppresses", PENDING, CaseSource.CURATED, listOf("§9"),
            tags = listOf("parallel-iteration", "invalid-index"),
            notes = "an EMPTY Department cell makes Supply's index invalid: references into Supply read 3VL UNKNOWN, suppressing the negative operator for Sales — only Ops (a valid Supply-only key) fires at Demand[-5]; the empty index cell itself draws the formal mandatoryField"),
        listOf(group("/Staffing", 1), field("/Staffing/PlanName", "Q3", 1, 1))
            + staffRow("/Staffing/Demand", 1, "Sales", "5")
            + staffRow("/Staffing/Supply", 1, "Ops", "2") + staffRow("/Staffing/Supply", 2, null, "4"),
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

    /** The F7 family directories — the iteration/correlation constructs (KERNEL-SEMANTICS §9). */
    private const val HAVING = "having"
    private const val FILTER = "filter"
    private const val CURRENT_REP = "current-repetition"
    private const val PARALLEL = "parallel-iteration"
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

    // ---- F7 row helpers ---------------------------------------------------------------------------

    /** One `/Order/Items` row: Sku (the index field), Count, and optionally UnitWeight (the Having filter column). */
    private fun itemRow(r: Int, sku: String, count: String, weight: String? = null): List<Placement> = buildList {
        add(group("/Order/Items", 1, r))
        add(field("/Order/Items/Sku", sku, 1, r, 1))
        add(field("/Order/Items/Count", count, 1, r, 1))
        weight?.let { add(field("/Order/Items/UnitWeight", it, 1, r, 1)) }
    }

    /** One `/Cart/Lines` row: ItemId (the index field, keyed by the row) + Qty. */
    private fun lineRow(r: Int, qty: String): List<Placement> = listOf(
        group("/Cart/Lines", 1, r),
        field("/Cart/Lines/ItemId", "K$r", 1, r, 1),
        field("/Cart/Lines/Qty", qty, 1, r, 1),
    )

    /** One Demand/Supply row: Department (the shared join index; null = the cell stays empty — an invalid
     *  index for the row, the shape `ParallelIterationDiffTest` pins) + Headcount. */
    private fun staffRow(groupPath: String, r: Int, dept: String?, headcount: String): List<Placement> = buildList {
        add(group(groupPath, 1, r))
        dept?.let { add(field("$groupPath/Department", it, 1, r, 1)) }
        add(field("$groupPath/Headcount", headcount, 1, r, 1))
    }

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
