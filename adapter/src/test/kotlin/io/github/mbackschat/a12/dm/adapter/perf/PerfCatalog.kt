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

    /** Every catalog scenario, in order — for the gate to sweep. */
    fun all(): List<PerfScenario> = listOf(b1(), b2(), b3(), b4())

    private data class FuzzRule(val group: String, val code: String, val errorRel: String, val cond: String)

    /**
     * A MULTI-rule scenario over the perf model whose instance is **random** — random row counts per group
     * AND random fill-or-empty (in-format) values, keyed off the `variant` as the RNG seed. The differential
     * fuzz net (`InterpreterKernelFuzzTest`) sweeps seeds and asserts interp == kernel on each generated
     * document. The rules use only supported operators with valid (referenced) error fields; the random
     * fill/empty exercises requiredness (empty index fields → `mandatoryField`), aggregate over varying rows,
     * and a cross-level reference — combinations the hand-written single-case `*DiffTest`s don't cover.
     */
    fun fuzz(): PerfScenario {
        val rules = listOf(
            FuzzRule(LINES, "HQ", "../Qty", "FieldFilled(Qty) And [Qty] > 50"),
            FuzzRule("/Perf", "TP", "../Lines/Amount", "Sum(Lines*/Amount) > 1000"),
            FuzzRule(SUBS, "HC", "../Cost", "FieldFilled(Cost) And [Cost] > 50"),
            FuzzRule(SUBS, "XL", "../Cost", "FieldFilled(Cost) And [Cost] > [/Perf/Lines/Qty]"),
        )
        val kernelModel = rules.fold(perfJson) { m, r -> RuntimeLaws.withRule(m, r.group, "R_${r.code}", r.errorRel, r.code, r.cond) }
        val interpModel = ModelDef(
            fields, rules.map { RuleDef("${it.group}/R_${it.code}", it.group, it.cond, it.code, it.errorRel) },
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        val fuzzRows: RowsFn = { g, _, rnd -> when (g) { LINES -> rnd.nextInt(0, 7); SUBS -> rnd.nextInt(0, 5); else -> 0 } }
        val fuzzValues = ValueStrategy { f, reps, rnd -> if (rnd.nextDouble() < 0.3) null else ValidValues.value(f, reps, rnd) }
        return PerfScenario("fuzz", kernelModel, interpModel) { sink, _, variant ->
            gen.populate(sink, seed = variant.toLong(), rowsOf = fuzzRows, value = fuzzValues)
        }
    }
}
