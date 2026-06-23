package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef

/**
 * The perf benchmark catalog (INTERPRETER-PERFORMANCE.md). Single-sources the `perf.dm.json` model — the
 * nested `/Perf` → `Lines`(LineKey, Qty, Amount) → `Subs`(SubKey, Cost) shape — so a scenario's kernel JSON
 * and interpreter [ModelDef] are built from ONE set of rule params and cannot drift. The shapes target the
 * **non-obvious group machinery**, not the flat single-row case:
 *
 * | id | shape | stresses |
 * |----|-------|----------|
 * | B1 | per-row comparison over Lines | baseline row iteration |
 * | B2 | star aggregate `Sum(Lines* / Amount)` at /Perf | star collection + fold |
 * | B3 | per-sub comparison over nested Subs | parallel index + iteration scope at depth |
 * | B4 | a Subs-row rule referencing the parent Line's Qty | common-prefix (cross-level) resolution per row |
 *
 * Index fields (LineKey/SubKey) are auto-required, so the instance generators FILL them — otherwise both
 * engines would emit `mandatoryField` and pollute the benchmark with extra firings.
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
        name: String, group: String, code: String, errorRel: String, cond: String,
        instance: (PlacementSink, Int, Int) -> Unit,
    ): PerfScenario {
        val kernelModel = RuntimeLaws.withRule(perfJson, group, "R_$code", errorRel, code, cond)
        val interpModel = ModelDef(
            fields, listOf(RuleDef("$group/R_$code", group, cond, code, errorRel)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        return PerfScenario(name, kernelModel, interpModel, instance)
    }

    // [variant] perturbs the numeric values (a `+variant` phase shift mod 100), so each repeated-eval instance
    // is a slightly-different document (different rows cross the comparison threshold) — mimicking a form edit
    // / the next batch record. variant = 0 is the canonical instance the single-document gate uses.

    /** [n] flat Lines, each LineKey + Qty (`(i+variant) % 100`) + Amount (`1`); the index field LineKey is filled. */
    private fun lines(sink: PlacementSink, n: Int, variant: Int) {
        for (i in 1..n) {
            sink.group(LINES, 1, i)
            sink.field(LINE_KEY, "L$i", 1, i, 1)
            sink.field(QTY, ((i + variant) % 100).toString(), 1, i, 1)
            sink.field(AMOUNT, "1", 1, i, 1)
        }
    }

    /** [n] Lines × [SUBS_PER_LINE] Subs; Qty = `(i+variant) % 100`, Cost = `(i*j+variant) % 100`; index fields filled. */
    private fun nested(sink: PlacementSink, n: Int, variant: Int) {
        for (i in 1..n) {
            sink.group(LINES, 1, i)
            sink.field(LINE_KEY, "L$i", 1, i, 1)
            sink.field(QTY, ((i + variant) % 100).toString(), 1, i, 1)
            sink.field(AMOUNT, "1", 1, i, 1)
            for (j in 1..SUBS_PER_LINE) {
                sink.group(SUBS, 1, i, j)
                sink.field(SUB_KEY, "S$i-$j", 1, i, j, 1)
                sink.field(COST, ((i * j + variant) % 100).toString(), 1, i, j, 1)
            }
        }
    }

    /** B1 — per-row comparison over Lines (`Qty > 50`): baseline row iteration. */
    fun b1(): PerfScenario =
        scenario("B1 per-row", LINES, "HQ", "../Qty", "FieldFilled(Qty) And [Qty] > 50", ::lines)

    /** B2 — star aggregate `Sum(Lines* / Amount) > 0` at /Perf: star collection + fold across N rows. The
     *  error field is the aggregated `Amount` itself (the kernel requires the error field to be referenced in
     *  the condition — `MVK_ERROR_FIELD_NOT_REFERENCED`). */
    fun b2(): PerfScenario =
        scenario("B2 star-aggregate", "/Perf", "TP", "../Lines/Amount", "Sum(Lines*/Amount) > 0", ::lines)

    /** B3 — per-sub comparison over nested Subs (`Cost > 50`): iteration-scope resolution at depth. */
    fun b3(): PerfScenario =
        scenario("B3 nested per-sub", SUBS, "HC", "../Cost", "FieldFilled(Cost) And [Cost] > 50", ::nested)

    /** B4 — a Subs-row rule referencing the parent Line's Qty (`Cost > [/Perf/Lines/Qty]`): cross-level walk per row. */
    fun b4(): PerfScenario =
        scenario("B4 cross-level", SUBS, "XL", "../Cost",
            "FieldFilled(Cost) And [Cost] > [/Perf/Lines/Qty]", ::nested)

    /** Every catalog scenario, in order — for the gate to sweep. */
    fun all(): List<PerfScenario> = listOf(b1(), b2(), b3(), b4())
}
