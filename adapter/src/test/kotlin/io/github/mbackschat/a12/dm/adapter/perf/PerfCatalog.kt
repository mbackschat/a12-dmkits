package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.random.Random

/** How many rows to instantiate for a repeatable group under a given parent row (the [SeedGenerator] hook). */
private typealias RowsFn = (group: String, parentReps: List<Int>, rnd: Random) -> Int

/**
 * The perf benchmark catalog (INTERPRETER-PERFORMANCE.md). Single-sources the `perf.dm.json` model — the
 * nested `/Perf` → `Lines`(LineKey, Qty, Amount) → `Subs`(SubKey, Cost) shape — so a scenario's kernel JSON
 * and interpreter [ModelDef] are built from ONE set of rule params and cannot drift. The instance is built by
 * the **model-driven [SeedGenerator]** (the same generator the differential fuzz net and a future `dmtool seed`
 * verb use): the structure comes from the model; a per-field [ValueStrategy] drives the numeric values so a
 * known fraction of rows crosses each rule's threshold. The shapes target the **non-obvious group machinery**:
 *
 * | id | shape | stresses |
 * |----|-------|----------|
 * | B1 | per-row comparison over Lines | baseline row iteration |
 * | B2 | star aggregate `Sum(Lines* / Amount)` at /Perf | star collection + fold |
 * | B3 | per-sub comparison over nested Subs | parallel index + iteration scope at depth |
 * | B4 | a Subs-row rule referencing the parent Line's Qty | common-prefix (cross-level) resolution per row |
 *
 * Index fields (LineKey/SubKey) are auto-required; the value strategy fills them, so no spurious
 * `mandatoryField` pollutes the benchmark.
 */
object PerfCatalog {

    private val perfJson = RuntimeLaws.model("models/perf.dm.json")

    const val LINE_KEY = "/Perf/Lines/LineKey"
    const val QTY = "/Perf/Lines/Qty"
    const val AMOUNT = "/Perf/Lines/Amount"
    const val SUB_KEY = "/Perf/Lines/Subs/SubKey"
    const val COST = "/Perf/Lines/Subs/Cost"

    /** Subs per Line for the nested scenarios (B3/B4): the scale knob N is the Line count, so total Sub rows = N × this. */
    const val SUBS_PER_LINE = 5

    private const val LINES = "/Perf/Lines"
    private const val SUBS = "/Perf/Lines/Subs"
    private const val DECLARED_MAX = 200_000   // perf.dm.json's `repeatability` for both repeatable groups

    private val fields = listOf(
        FieldDef(LINE_KEY, Kind.STRING), FieldDef(QTY, Kind.NUMBER), FieldDef(AMOUNT, Kind.NUMBER),
        FieldDef(SUB_KEY, Kind.STRING), FieldDef(COST, Kind.NUMBER),
    )

    /** Build a single-rule validate scenario for BOTH engines from one set of rule params — no drift. */
    private fun scenario(
        name: String, group: String, code: String, errorRel: String, cond: String, rows: (Int) -> RowsFn,
    ): PerfScenario {
        val kernelModel = RuntimeLaws.withRule(perfJson, group, "R_$code", errorRel, code, cond)
        val interpModel = ModelDef(
            fields, listOf(RuleDef("$group/R_$code", group, cond, code, errorRel)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario(name, kernelModel, interpModel) { sink, n, variant ->
            gen.populate(sink, rowsOf = rows(n), value = perfValues(variant))
        }
    }

    // [variant] phase-shifts the numeric values (`+variant` mod 100) so each repeated-eval instance is a
    // slightly-different document — mimicking a form edit / the next batch record. The reps indices are the
    // field's full 1-based tuple: Qty is [/Perf=1, Lines=i, Qty=1] (so reps[1]=i); Cost is [1, i, j, 1].
    private fun perfValues(variant: Int): ValueStrategy = ValueStrategy { field, reps, _ ->
        when (field.path) {
            QTY -> ((reps[1] + variant) % 100).toString()
            COST -> ((reps[1] * reps[2] + variant) % 100).toString()
            AMOUNT -> "1"
            LINE_KEY, SUB_KEY -> "K" + reps.joinToString("_")
            else -> "0"
        }
    }

    private fun flatRows(n: Int): RowsFn = { g, _, _ -> if (g == LINES) n else 0 }
    private fun nestedRows(n: Int): RowsFn = { g, _, _ -> when (g) { LINES -> n; SUBS -> SUBS_PER_LINE; else -> 0 } }

    /** B1 — per-row comparison over Lines (`Qty > 50`): baseline row iteration. */
    fun b1(): PerfScenario =
        scenario("B1 per-row", LINES, "HQ", "../Qty", "FieldFilled(Qty) And [Qty] > 50", ::flatRows)

    /** B2 — star aggregate `Sum(Lines* / Amount) > 0` at /Perf: star collection + fold across N rows. The error
     *  field is the aggregated `Amount` itself (the kernel requires the error field be referenced in the
     *  condition — `MVK_ERROR_FIELD_NOT_REFERENCED`). */
    fun b2(): PerfScenario =
        scenario("B2 star-aggregate", "/Perf", "TP", "../Lines/Amount", "Sum(Lines*/Amount) > 0", ::flatRows)

    /** B3 — per-sub comparison over nested Subs (`Cost > 50`): iteration-scope resolution at depth. */
    fun b3(): PerfScenario =
        scenario("B3 nested per-sub", SUBS, "HC", "../Cost", "FieldFilled(Cost) And [Cost] > 50", ::nestedRows)

    /** B4 — a Subs-row rule referencing the parent Line's Qty (`Cost > [/Perf/Lines/Qty]`): cross-level walk per row. */
    fun b4(): PerfScenario =
        scenario("B4 cross-level", SUBS, "XL", "../Cost",
            "FieldFilled(Cost) And [Cost] > [/Perf/Lines/Qty]", ::nestedRows)

    /** B5 — deep nested-star aggregate `Sum(Lines* / Subs* / Cost) > 0` at /Perf: nested-star collection across
     *  BOTH repetition levels (every Sub of every Line folded into one sum), the hardest aggregate shape. */
    fun b5(): PerfScenario =
        scenario("B5 deep-nested-star", "/Perf", "DN", "../Lines/Subs/Cost",
            "Sum(Lines*/Subs*/Cost) > 0", ::nestedRows)

    /** The B6 many-rules set (12): per-row, cross-field, threshold, aggregates (incl. deep nested-star),
     *  nested per-sub, cross-level — a realistic complex model. Codes unique within the model; all forms are
     *  kernel-valid (CurrentRepetition is covered by the fuzz net + its own diff test, not needed here). */
    private fun b6Rules(): List<FuzzRule> = listOf(
        FuzzRule(LINES, "HQ", "../Qty", "FieldFilled(Qty) And [Qty] > 50"),
        FuzzRule(LINES, "HA", "../Amount", "FieldFilled(Amount) And [Amount] > 50"),
        FuzzRule(LINES, "VQ", "../Qty", "FieldFilled(Qty) And [Qty] > 90"),
        FuzzRule(LINES, "AX", "../Amount", "FieldFilled(Qty) And FieldFilled(Amount) And [Amount] > [Qty]"),
        FuzzRule(LINES, "LQ", "../Qty", "FieldFilled(Qty) And [Qty] < 10"),
        FuzzRule("/Perf", "TA", "../Lines/Amount", "Sum(Lines*/Amount) > 1000"),
        FuzzRule("/Perf", "TQ", "../Lines/Qty", "Sum(Lines*/Qty) > 1000"),
        FuzzRule("/Perf", "DN", "../Lines/Subs/Cost", "Sum(Lines*/Subs*/Cost) > 5000"),
        FuzzRule(SUBS, "HC", "../Cost", "FieldFilled(Cost) And [Cost] > 50"),
        FuzzRule(SUBS, "VC", "../Cost", "FieldFilled(Cost) And [Cost] > 90"),
        FuzzRule(SUBS, "XL", "../Cost", "FieldFilled(Cost) And [Cost] > [/Perf/Lines/Qty]"),
        FuzzRule(SUBS, "LC", "../Cost", "FieldFilled(Cost) And [Cost] < 10"),
    )

    /** B6 — MANY rules over the nested model: whole-document throughput where the interpreter's per-call rule
     *  re-parse (the #31 "prepare once" cost) bites hardest (the kernel codegens all rules once). */
    fun b6(): PerfScenario {
        val rules = b6Rules()
        val kernelModel = rules.fold(perfJson) { m, r -> RuntimeLaws.withRule(m, r.group, "R_${r.code}", r.errorRel, r.code, r.cond) }
        val interpModel = ModelDef(
            fields, rules.map { RuleDef("${it.group}/R_${it.code}", it.group, it.cond, it.code, it.errorRel) },
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("B6 many-rules", kernelModel, interpModel) { sink, n, variant ->
            gen.populate(sink, rowsOf = nestedRows(n), value = perfValues(variant))
        }
    }

    /** Every catalog scenario, in order — for the gate to sweep. */
    fun all(): List<PerfScenario> = listOf(b1(), b2(), b3(), b4(), b5(), b6())

    private data class FuzzRule(val group: String, val code: String, val errorRel: String, val cond: String)

    // Unambiguously-malformed values (scale-independent) for the kinds the interpreter formal-checks. NUMBER:
    // invalid chars / signed-zero / leading-plus / leading-blank. DATE: garbage / a value in the WRONG format
    // for the field (ISO on a de field, de on an ISO field) — each must produce the kernel's formal code and
    // suppress the referencing rule, identically in both engines. Other kinds stay valid (not yet formal-checked).
    private fun malformed(field: io.github.mbackschat.a12.dm.interpreter.model.FieldDef, rnd: Random): String? = when (field.kind) {
        Kind.NUMBER -> listOf("abc", "-0", "+5", " 5 ").let { it[rnd.nextInt(it.size)] }
        Kind.DATE -> {
            val wrongFormat = if (field.format == "dd.MM.yyyy") "2024-06-15" else "15.06.2024"
            listOf("not-a-date", wrongFormat).let { it[rnd.nextInt(it.size)] }
        }
        else -> null
    }

    // The fuzz uses its OWN model (`fuzz.dm.json`, own e-commerce domain) — a deliberately DIVERSE shape the
    // gate's perf model lacks: STRING + NUMBER (scale 0 and 2) + an ISO date + a NON-ISO (`dd.MM.yyyy`) date +
    // BOOLEAN, nested two levels. The non-ISO date is the high-value bit: it exercises locale-variant date
    // canonicalization under fuzz. Only supported operators, valid (referenced) error fields.
    private val fuzzJson = RuntimeLaws.model("models/fuzz.dm.json")
    private const val FZ_LINES = "/Cart/Lines"
    private const val FZ_PARTS = "/Cart/Lines/Parts"
    private val fuzzFields = listOf(
        FieldDef("/Cart/Lines/ItemId", Kind.STRING),
        FieldDef("/Cart/Lines/Qty", Kind.NUMBER, scale = 0),
        FieldDef("/Cart/Lines/Price", Kind.NUMBER, scale = 2),
        FieldDef("/Cart/Lines/OrderedOn", Kind.DATE, format = "yyyy-MM-dd"),
        FieldDef("/Cart/Lines/DueBy", Kind.DATE, format = "dd.MM.yyyy"),
        FieldDef("/Cart/Lines/Active", Kind.BOOLEAN),
        FieldDef("/Cart/Lines/Parts/PartId", Kind.STRING),
        FieldDef("/Cart/Lines/Parts/Weight", Kind.NUMBER, scale = 2),
    )

    /**
     * The fuzz net's rule specs, single-sourced so the catalog-parity meter (`adapter.laws.CatalogParityTest`)
     * can read which constructs the fuzz exercises WITHOUT building the kernel model. Keeping this the one source
     * means a rule added here is automatically seen by both the fuzz net and the parity guard.
     */
    private fun fuzzRuleSpecs(): List<FuzzRule> =
        listOf(
            FuzzRule(FZ_LINES, "HQ", "../Qty", "FieldFilled(Qty) And [Qty] > 50"),
            FuzzRule(FZ_LINES, "YO", "../OrderedOn", "FieldFilled(OrderedOn) And YearFromDate(OrderedOn) > 2020"),
            FuzzRule(FZ_LINES, "YD", "../DueBy", "FieldFilled(DueBy) And YearFromDate(DueBy) > 2020"),
            FuzzRule("/Cart", "TP", "../Lines/Price", "Sum(Lines*/Price) > 1000"),
            FuzzRule("/Cart", "FF", "../Lines/Qty", "FirstFilledValue(Lines*/Qty) > 50"),   // first specified row, skip empties
            FuzzRule("/Cart", "SP", "../Lines/Qty", "SumOfProducts(Lines*/Qty, Lines*/Price) > 100000"),   // Σ qᵢ·pᵢ, row-paired
            FuzzRule(FZ_LINES, "DV", "../Price", "FieldFilled(Qty) And { [Price] / [Qty] } > 5"),   // division (+ div-by-zero quiet)
            FuzzRule(FZ_LINES, "PW", "../Qty", "{ [Qty] ^ 2 } > 100"),                                // power
            FuzzRule(FZ_LINES, "AB", "../Price", "Abs([Price]) > 500"),                               // |x|
            FuzzRule(FZ_LINES, "LN", "../ItemId", "FieldFilled(ItemId) And Length(ItemId) > 1"),      // string length (+ uniqueIndex suppression on dup keys)
            FuzzRule(FZ_LINES, "NV", "../Qty", "FieldFilled(Qty) And NumberOfValueInFields(0 In Qty, Price) >= 1"), // count-of-value (In syntax; empty not counted)
            FuzzRule(FZ_LINES, "RS", "../ItemId", """FieldFilled(ItemId) And RangeAsString(ItemId, 1, 2) == "K0""""), // substring of the index pool
            FuzzRule(FZ_LINES, "RN", "../ItemId", "FieldFilled(ItemId) And RangeAsNumber(ItemId, 2, 2) >= 1"),         // substring-as-number (\\d+ else 0)
            FuzzRule(FZ_LINES, "FS", "../Qty", """FieldFilled(Qty) And FieldValueAsString(Qty) == "0""""),            // number→string raw-value coercion
            FuzzRule(FZ_LINES, "AF", "../Qty", "AllFieldsFilled(Qty, Price)"),   // field-list quantifier (3VL on a malformed operand)
            FuzzRule(FZ_LINES, "FU", "../Qty", "FieldValuesNotUnique(Qty, Price)"),   // fires when Qty == Price numerically (cross-scale); empties skipped
            FuzzRule(FZ_LINES, "DT", "../Qty", "[Qty] DiffersWithToleranceRange5 [Price]"),   // |Qty − Price| > 5 (strict band; empty→0)

            FuzzRule(FZ_LINES, "RD", "../Price", "FieldFilled(Price) And RoundDown([Price], 0) > 50"), // FLOOR (signed Price → negatives)
            FuzzRule(FZ_LINES, "RA", "../Price", "FieldFilled(Price) And RoundAccounting([Price] * [Qty], 1) > 100"), // HALF_UP over a product
            FuzzRule(FZ_PARTS, "HW", "../Weight", "FieldFilled(Weight) And [Weight] > 50"),
            FuzzRule(FZ_PARTS, "RW", "../Weight", "FieldFilled(Weight) And RoundUpValue(Weight) > 50"), // CEILING, *Value field form
            FuzzRule("/Cart", "CR", "../Lines/Qty", "FieldFilled(Lines/Qty) And CurrentRepetition(Lines) > 2"), // per-row index
            FuzzRule(FZ_PARTS, "XL", "../Weight", "FieldFilled(Weight) And [Weight] > [/Cart/Lines/Qty]"),
        )

    /** The fuzz rules' condition texts — what [CatalogParityTest] scans to know which constructs the net covers. */
    fun fuzzConditions(): List<String> = fuzzRuleSpecs().map { it.cond }

    /**
     * A MULTI-rule scenario over the diverse [fuzzJson] model whose instance is **random** — random row counts
     * per group AND random fill-or-empty (in-format) values, keyed off the `variant` as the RNG seed. The
     * differential fuzz net (`InterpreterKernelFuzzTest`) sweeps seeds and asserts interp == kernel on each
     * generated document. The rules use only supported operators with valid (referenced) error fields; the
     * random fill/empty + diverse kinds exercise requiredness (empty index fields → `mandatoryField`), a
     * per-row comparison, **date extractors over an ISO AND a non-ISO date field**, an aggregate over varying
     * rows, **`FirstFilledValue`** (first-specified-row, skip-empties), **`NumberOfValueInFields`** (count-of-
     * value, `In` syntax), **rounding (FLOOR/CEILING/HALF_UP, expr + `*Value` forms)**, **the substring coercions
     * `RangeAsString`/`RangeAsNumber` + `FieldValueAsString`**, **`SumOfProducts`** (row-paired),
     * **`CurrentRepetition`** (per-row index), **`AllFieldsFilled`** (a field-list quantifier — exercises the
     * 3VL on a malformed operand), a nested per-part rule, and a cross-level reference — combinations no
     * hand-written `*DiffTest` covers.
     */
    fun fuzz(): PerfScenario {
        val rules = fuzzRuleSpecs()
        val kernelModel = rules.fold(fuzzJson) { m, r -> RuntimeLaws.withRule(m, r.group, "R_${r.code}", r.errorRel, r.code, r.cond) }
        val interpModel = ModelDef(
            fuzzFields, rules.map { RuleDef("${it.group}/R_${it.code}", it.group, it.cond, it.code, it.errorRel) },
            repeatableGroups = setOf(FZ_LINES, FZ_PARTS),
            declaredReps = mapOf(FZ_LINES to 1000, FZ_PARTS to 1000),
            indexFields = setOf("/Cart/Lines/ItemId", "/Cart/Lines/Parts/PartId"),
        )
        val gen = SeedGenerator(interpModel)
        val fuzzRows: RowsFn = { g, _, rnd -> when (g) { FZ_LINES -> rnd.nextInt(0, 7); FZ_PARTS -> rnd.nextInt(0, 5); else -> 0 } }
        // Fill 70%, leave 30% empty (→ requiredness); of the filled, ~15% are deliberately MALFORMED for the
        // kinds the interpreter formal-checks (NUMBER/DATE) — so the fuzz also exercises the formal-error +
        // rule-suppression machinery. Index fields draw from a SMALL pool so values collide → the auto
        // `uniqueIndex` check is exercised too (and empties still hit requiredness).
        val indexFields = setOf("/Cart/Lines/ItemId", "/Cart/Lines/Parts/PartId")
        val fuzzValues = ValueStrategy { f, reps, rnd ->
            when {
                rnd.nextDouble() < 0.3 -> null
                f.path in indexFields -> "K" + rnd.nextInt(3)
                rnd.nextDouble() < 0.15 -> malformed(f, rnd) ?: ValidValues.value(f, reps, rnd)
                else -> ValidValues.value(f, reps, rnd)
            }
        }
        return PerfScenario("fuzz", kernelModel, interpModel) { sink, _, variant ->
            gen.populate(sink, seed = variant.toLong(), rowsOf = fuzzRows, value = fuzzValues)
        }
    }
}
