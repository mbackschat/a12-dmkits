package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.adapter.laws.TypedRules
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.edit.GroupEditor
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.modelkit.DateTimeFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance
import java.util.function.Consumer
import kotlin.random.Random

/** How many rows to instantiate for a repeatable group under a given parent row (the [SeedGenerator] hook). */
private typealias RowsFn = (group: String, parentReps: List<Int>, rnd: Random) -> Int

/**
 * The perf benchmark catalog (INTERPRETER-PERFORMANCE.md). Single-sources the `perf.dm.json` model — the
 * nested `/Perf` → `Lines`(LineKey, Qty, Amount) → `Subs`(SubKey, Cost) shape — so a scenario's kernel JSON
 * and interpreter [ModelDef] are built from ONE set of rule params and cannot drift. The instance is built by
 * the **model-driven [SeedGenerator]** (the same generator the differential fuzz net and the `dmtool model seed`
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
 *
 * MOST scenarios carry **no DATE_TIME field**, so the zone axis is inert for them (dates/numbers/strings are
 * zone-free) and their interpreter [ModelDef]s omit `timeZone`. The **[berlinDateTime]/[utcDateTimeControl]**
 * pair (IF132) is the exception: it declares DATE_TIME fields and threads `timeZone` into its ModelDef, so the
 * `berlinOffsetSeconds` civil-calendar path (IF128/IF131) is exercised at scale. When adding another datetime
 * scenario, thread `timeZone = ModelTimeZone.EUROPE_BERLIN` into its ModelDef — a mismatch is not silent (the
 * differential fails loud on any straddling generated value; the DST family: `laws.DstTimeZoneDiffTest`).
 */
object PerfCatalog {

    private val perfJson = RuntimeLaws.model("models/perf.dm.json")

    // Every scenario authors its rules/computations as the dmkit's own TYPED objects through the EDITOR surface
    // ([EditSession] → [GroupEditor]): a session on the model hands out group editors that yield typed refs by
    // RELATIVE child name (no full paths re-typed; fail-fast on a bad name/kind; flatten() for star aggregates),
    // and a rule is a [Rule] whose error field + condition are refs and whose kernel-relative error path +
    // canonical text are DERIVED. [TypedRules] feeds one such typed object to BOTH engines — no hand-written
    // "../…" error paths, no condition/operation strings. (Eat your own dogfood: the benchmark consumes the kits.)
    private val perf = EditSession.read(perfJson)
    private val perfRoot = perf.group("/Perf")            // anchor for the /Perf-level star aggregates
    private val lines = perf.group(LINES)                 // editor on the repeatable Lines group
    private val subs = perf.group(SUBS)                   // editor on the nested repeatable Subs group

    /** Author an ERROR rule off this group editor: name `R_<code>`, the error field + condition as refs. The
     *  severity + dummy message satisfy [Rule] (the kernel harness supplies its own message); the error path
     *  and the rendered condition are derived by the [Rule], never hand-typed. */
    private fun GroupEditor.errorRule(code: String, errorField: FieldRef, cond: Condition): Rule =
        rule { it.name("R_$code").code(code).errorField(errorField).`when`(cond).severity(Severity.ERROR).message(Dsl.en("x")).build() }

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

    // Typed, VALIDATED number handles by RELATIVE child name off the group editors — bound once and reused
    // (the full path lives only in the editor's group path; .num() throws on a bad name/kind).
    private val qty = lines.num("Qty")
    private val amount = lines.num("Amount")
    private val cost = subs.num("Cost")

    /** Build a single-rule validate scenario for BOTH engines from ONE typed [Rule] — no drift, no strings. */
    private fun scenario(name: String, rule: Rule, rows: (Int) -> RowsFn): PerfScenario {
        val kernelModel = TypedRules.inject(perfJson, rule)
        val interpModel = ModelDef(
            fields, listOf(TypedRules.def(rule)),
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
        scenario("B1 per-row", lines.errorRule("HQ", qty.field(), qty.filled().and(qty.gt(50))), ::flatRows)

    /** **PRA — a per-row rule comparing each Line to a ROW-INVARIANT global aggregate** (`[Amount] > Sum(Lines* /
     *  Amount)`): the Sum over ALL lines is the same for every line. It caught the old per-row refold (O(rows²));
     *  the validation-call aggregate memo now folds once per star scope and the routine perf gate locks it. */
    fun perRowGlobalAggregate(): PerfScenario =
        scenario("PRA per-row-global-agg",
            lines.errorRule("PGA", amount.field(), amount.filled().and(amount.asNumber().gt(Dsl.sum(perfRoot.flatten("Lines*/Amount"))))),
            ::flatRows)

    /** **FVNU — `FieldValuesNotUnique` over a large star** (`Lines* / Amount`): uniqueness across the repetition's
     *  rows. With all-distinct values it never fires but runs the FULL pairwise scan — O(rows²) if naive. Each
     *  Line's Amount is unique (the row index). */
    fun fieldValuesNotUniqueStar(): PerfScenario {
        val rule = perfRoot.errorRule("FVNU", amount.field(), Dsl.fieldValuesNotUnique(perfRoot.flatten("Lines*/Amount")))
        val kernelModel = TypedRules.inject(perfJson, rule)
        val interpModel = ModelDef(
            fields, listOf(TypedRules.def(rule)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX), indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("FVNU uniqueness", kernelModel, interpModel) { sink, n, _ ->
            gen.populate(sink, rowsOf = flatRows(n), value = ValueStrategy { field, reps, _ ->
                when (field.path) {
                    LINE_KEY -> "K" + reps.joinToString("_")
                    AMOUNT -> reps[1].toString()   // unique per line → all distinct → full pairwise scan, no early-out
                    else -> "0"
                }
            })
        }
    }

    /** **NDV — `NumberOfDifferentValues` over a large star** (`> 999999`, never fires): one aggregate eval whose
     *  distinct count was computed by a linear scan per element → O(values²). Each Line's Amount is UNIQUE (the
     *  row index) so the distinct set grows to N — the worst case that exposes the quadratic. */
    fun numDifferentValues(): PerfScenario {
        val rule = perfRoot.errorRule("NDV", amount.field(), Dsl.numberOfDifferentValues(perfRoot.flatten("Lines*/Amount")).gt(999999))
        val kernelModel = TypedRules.inject(perfJson, rule)
        val interpModel = ModelDef(
            fields, listOf(TypedRules.def(rule)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX), indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("NDV distinct-count", kernelModel, interpModel) { sink, n, _ ->
            gen.populate(sink, rowsOf = flatRows(n), value = ValueStrategy { field, reps, _ ->
                when (field.path) {
                    LINE_KEY -> "K" + reps.joinToString("_")
                    AMOUNT -> reps[1].toString()   // unique per line → distinct set grows to N (worst case)
                    else -> "0"
                }
            })
        }
    }

    /** B2 — star aggregate `Sum(Lines* / Amount) > 0` at /Perf: star collection + fold across N rows. The error
     *  field is the aggregated `Amount` itself (the kernel requires the error field be referenced in the
     *  condition — `MVK_ERROR_FIELD_NOT_REFERENCED`). */
    fun b2(): PerfScenario =
        scenario("B2 star-aggregate",
            perfRoot.errorRule("TP", amount.field(), Dsl.sum(perfRoot.flatten("Lines*/Amount")).gt(0)), ::flatRows)

    /** B3 — per-sub comparison over nested Subs (`Cost > 50`): iteration-scope resolution at depth. */
    fun b3(): PerfScenario =
        scenario("B3 nested per-sub", subs.errorRule("HC", cost.field(), cost.filled().and(cost.gt(50))), ::nestedRows)

    /** B4 — a Subs-row rule referencing the parent Line's Qty (`Cost > [/Perf/Lines/Qty]`): cross-level walk per row. */
    fun b4(): PerfScenario =
        scenario("B4 cross-level", subs.errorRule("XL", cost.field(), cost.filled().and(cost.gt(qty))), ::nestedRows)

    /** B5 — deep nested-star aggregate `Sum(Lines* / Subs* / Cost) > 0` at /Perf: nested-star collection across
     *  BOTH repetition levels (every Sub of every Line folded into one sum), the hardest aggregate shape. */
    fun b5(): PerfScenario =
        scenario("B5 deep-nested-star",
            perfRoot.errorRule("DN", cost.field(), Dsl.sum(perfRoot.flatten("Lines*/Subs*/Cost")).gt(0)), ::nestedRows)

    /** **Multi-star `$`-correlation** (IF144) — a per-Sub `MoreThanOneFieldFilled(Lines* then Subs* then Cost
     *  Having [Cost] == [$Cost])`: each Sub correlates against EVERY Line's Subs by equal Cost. Before IF144 the
     *  multi-star operand forced the O(N²) naive scan (interp measured worse than the kernel, ratio 0.51→1.21);
     *  the first-star-scope hash join makes it O(rows). Costs cycle by SUB index (`msCorrValues`) so every Line's
     *  same-index Subs share a Cost → large cross-Line buckets, the worst case for the correlation. */
    fun multiStarCorrelation(): PerfScenario {
        val rule = subs.errorRule("MSC", cost.field(), cost.filled().and(
            Dsl.moreThanOneFieldFilled(perfRoot.flatten("Lines*/Subs*/Cost").having(cost.eq(cost.outer())))))
        val kernelModel = TypedRules.inject(perfJson, rule)
        val interpModel = ModelDef(
            fields, listOf(TypedRules.def(rule)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX), indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("MSC multi-star correlation", kernelModel, interpModel) { sink, n, variant ->
            gen.populate(sink, rowsOf = nestedRows(n), value = msCorrValues(variant))
        }
    }

    /** Costs keyed by SUB index (not Line) so same-index Subs across ALL Lines collide — the big cross-Line
     *  buckets that stress the multi-star correlation; index keys stay unique. */
    private fun msCorrValues(variant: Int): ValueStrategy = ValueStrategy { field, reps, _ ->
        when (field.path) {
            LINE_KEY, SUB_KEY -> "K" + reps.joinToString("_")
            COST -> ((reps[2] + variant) % 20).toString()   // depends on the SUB index → collides across Lines
            else -> "0"
        }
    }

    /** The B6 many-rules set (12): per-row, cross-field, threshold, aggregates (incl. deep nested-star),
     *  nested per-sub, cross-level — a realistic complex model. Codes unique within the model; all forms are
     *  kernel-valid (CurrentRepetition is covered by the fuzz net + its own diff test, not needed here). */
    private fun b6Rules(): List<Rule> = listOf(
        lines.errorRule("HQ", qty.field(), qty.filled().and(qty.gt(50))),
        lines.errorRule("HA", amount.field(), amount.filled().and(amount.gt(50))),
        lines.errorRule("VQ", qty.field(), qty.filled().and(qty.gt(90))),
        lines.errorRule("AX", amount.field(), qty.filled().and(amount.filled()).and(amount.gt(qty))),
        lines.errorRule("LQ", qty.field(), qty.filled().and(qty.lt(10))),
        perfRoot.errorRule("TA", amount.field(), Dsl.sum(perfRoot.flatten("Lines*/Amount")).gt(1000)),
        perfRoot.errorRule("TQ", qty.field(), Dsl.sum(perfRoot.flatten("Lines*/Qty")).gt(1000)),
        perfRoot.errorRule("DN", cost.field(), Dsl.sum(perfRoot.flatten("Lines*/Subs*/Cost")).gt(5000)),
        subs.errorRule("HC", cost.field(), cost.filled().and(cost.gt(50))),
        subs.errorRule("VC", cost.field(), cost.filled().and(cost.gt(90))),
        subs.errorRule("XL", cost.field(), cost.filled().and(cost.gt(qty))),
        subs.errorRule("LC", cost.field(), cost.filled().and(cost.lt(10))),
    )

    /** B6 — MANY rules over the nested model: whole-document throughput where the kernel's compile-once codegen
     *  most out-runs the interpreter's per-call tree-walk (rules are parsed once + cached — `ParsedModel`; the
     *  #31 re-parse cost is retired). */
    fun b6(): PerfScenario {
        val rules = b6Rules()
        val kernelModel = rules.fold(perfJson) { json, r -> TypedRules.inject(json, r) }
        val interpModel = ModelDef(
            fields, rules.map { TypedRules.def(it) },
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("B6 many-rules", kernelModel, interpModel) { sink, n, variant ->
            gen.populate(sink, rowsOf = nestedRows(n), value = perfValues(variant))
        }
    }

    /**
     * A parameterized many-rules scenario: [count] kernel-valid rules over the nested model, cycling per-row /
     * cross-field / nested / aggregate templates with varying thresholds (unique codes). The scale knob is the
     * **rule count**, used by the kernel-edge sweep to find where the interpreter's per-call tree-walk (rules
     * parsed once + cached — `ParsedModel`) loses to the kernel's compile-once codegen — its documented advantage.
     */
    fun manyRules(count: Int, firing: Boolean = true): PerfScenario {
        val rules = (0 until count).map { i ->
            val code = "R%03d".format(i)
            when (i % 4) {
                0 -> lines.errorRule(code, qty.field(), qty.filled().and(qty.gt(50L + i)))
                1 -> lines.errorRule(code, amount.field(), qty.filled().and(amount.filled()).and(amount.gt(qty)))
                2 -> subs.errorRule(code, cost.field(), cost.filled().and(cost.gt(40L + (i % 50))))
                else -> perfRoot.errorRule(code, amount.field(), Dsl.sum(perfRoot.flatten("Lines*/Amount")).gt(1000L + i))
            }
        }
        val kernelModel = rules.fold(perfJson) { json, r -> TypedRules.inject(json, r) }
        val interpModel = ModelDef(
            fields, rules.map { TypedRules.def(it) },
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        val name = if (firing) "manyRules($count)" else "manyRules($count,low-firing)"
        return PerfScenario(name, kernelModel, interpModel) { sink, n, variant ->
            gen.populate(sink, rowsOf = nestedRows(n), value = if (firing) perfValues(variant) else lowFiringValues(variant))
        }
    }

    /**
     * The **low-firing** counterpart of [perfValues] (the realistic *valid-document* regime — most rules PASS):
     * every numeric field is a small `1`, below every `manyRules` threshold (`Qty>50+i`, `Cost>40+i`,
     * `Amount>Qty` → `1>1` false, the `Sum(Lines* then Amount) > 1000+i` aggregate → `n` Lines × 1 = `n` ≤ 1000 < 1003) → **0 fires**
     * at the benchmark's N=1000. Index keys stay UNIQUE (else duplicate-index errors would fire). This is the
     * regime where per-row evaluation (firedType + polarity) dominates and exposes the strategy difference: the
     * kernel's two-phase guard versus the interpreter's deliberate lazy-polarity single walk (the attempted
     * two-phase interpreter variant measured slower); the firing-heavy [perfValues] hides it behind message-building.
     */
    private fun lowFiringValues(variant: Int): ValueStrategy = ValueStrategy { field, reps, _ ->
        when (field.path) {
            LINE_KEY, SUB_KEY -> "K" + reps.joinToString("_")   // unique → no duplicate-index firing
            QTY, COST, AMOUNT -> "1"                            // below every threshold → rules pass
            else -> "0"
        }
    }

    /**
     * A **realistically DISTRIBUTED** many-rules scenario: [count] rules, each on its OWN field (`F0..F{count-1}`,
     * one rule per field, under the repeatable `Lines` group), so the rules spread across [count] DISTINCT error
     * fields instead of piling onto ~3 the way [manyRules] does. This is the "sensible model" shape (a wide form
     * where each field carries its own validation), and it matters for the kernel: its Groovy codegen splits the
     * generated validator by ERROR-FIELD count (10 fields/method — `CodeGenTeilValidierung`), so concentrated
     * rules collapse into ONE method and blow the JVM 64 KB method limit (~512–768 rules), while distributed
     * rules fan out across many small methods and compile. The interpreter (no codegen) is indifferent to the
     * shape. Low-firing by default (each field `1`, below its `> 50+i` threshold → 0 fires — the valid-doc case).
     */
    fun manyRulesDistributed(count: Int, firing: Boolean = false): PerfScenario {
        val distJson = distributedModelJson(count)
        val distLines = EditSession.read(distJson).group(LINES)
        val rules = (0 until count).map { i ->
            val f = distLines.num("F$i")
            distLines.errorRule("R%04d".format(i), f.field(), f.filled().and(f.gt(50L + i)))
        }
        val kernelModel = rules.fold(distJson) { json, r -> TypedRules.inject(json, r) }
        val distFields = buildList {
            add(FieldDef(LINE_KEY, Kind.STRING))
            for (i in 0 until count) add(FieldDef("/Perf/Lines/F$i", Kind.NUMBER))
        }
        val interpModel = ModelDef(
            distFields, rules.map { TypedRules.def(it) },
            repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to DECLARED_MAX), indexFields = setOf(LINE_KEY),
        )
        val gen = SeedGenerator(interpModel)
        val name = "manyRulesDistributed($count${if (firing) "" else ",low-firing"})"
        return PerfScenario(name, kernelModel, interpModel) { sink, n, _ ->
            gen.populate(sink, rowsOf = flatRows(n), value = distributedValues(firing))
        }
    }

    /** Per-field values for [manyRulesDistributed]: each `Fi` is `1` (low-firing, below its `> 50+i` bound) or
     *  just above it (`51+i`, firing); the line key stays unique (no duplicate-index firing). */
    private fun distributedValues(firing: Boolean): ValueStrategy = ValueStrategy { field, reps, _ ->
        val idx = field.path.removePrefix("/Perf/Lines/F").toIntOrNull()
        when {
            idx != null -> if (firing) (51 + idx).toString() else "1"
            field.path == LINE_KEY -> "K" + reps.joinToString("_")
            else -> "0"
        }
    }

    /** A DM-JSON model with [numFields] flat NUMBER fields (`F0..F{numFields-1}`) under the repeatable `Lines`
     *  group — the base model [manyRulesDistributed] injects its one-rule-per-field set into. Built as a string
     *  (the field block is the only variable part). NB: keys use the spaced `": "` form because
     *  [RuntimeLaws.withRule] locates the injection group by string-searching for `"id": "<group>"` literally
     *  (a compact `"id":"…"` would miss, mis-inserting the rule). */
    private fun distributedModelJson(numFields: Int): String {
        val fieldsJson = (0 until numFields).joinToString(", ") { i ->
            """{"type": "Field", "id": "/Perf/Lines/F$i", "name": "F$i", "Field": {"fieldType": {"type": "NumberType", "NumberType": {"maxFractionalDigits": 0}}}}"""
        }
        return """{"header": {"id": "perf", "modelType": "document", "modelVersion": "28.4.0", "locales": [{"code": "en_US"}], "labels": [], "annotations": [], "modelReferences": []}, "content": {"modelInfo": {"name": "perf", "variant": "cockpit", "revision": "Arbeitsversion", "immutable": false}, "modelConfig": {"decimalSeparator": ".", "timeZone": "Europe/Berlin", "conditionLanguage": {"code": "en_US"}, "fieldRefByShortNameAllowed": true}, "modelRoot": {"rootGroups": [{"type": "Group", "id": "/Perf", "name": "Perf", "Group": {"repeatability": 1, "elements": [{"type": "Group", "id": "/Perf/Lines", "name": "Lines", "Group": {"repeatability": 200000, "indexFieldName": "LineKey", "elements": [{"type": "Field", "id": "/Perf/Lines/LineKey", "name": "LineKey", "Field": {"fieldType": {"type": "StringType", "StringType": {}}}}, $fieldsJson]}}]}}]}}}"""
    }

    // ---- deep nesting (4 repeatable levels) + RICH rules: cross-level (1 & 3 parents up), Having-filtered
    //      Sum, deep nested-star Sum (the "sensible rules across a deep DM" benchmark). E-commerce catalog:
    //      Vendor > Product > Offer > Tier, each repeatable + index-keyed. ------------------------------------
    private const val VENDOR = "/Cat/Vendor"
    private const val PRODUCT = "/Cat/Vendor/Product"
    private const val OFFER = "/Cat/Vendor/Product/Offer"
    private const val TIER = "/Cat/Vendor/Product/Offer/Tier"

    /** A 4-level nested repeatable model (Vendor ▸ Product ▸ Offer ▸ Tier), built with the structure builder. */
    private val deepJson: String by lazy {
        DocumentModelDraft.named("cat").locale("en_US")
            .rootGroup("Cat", Consumer { root ->
                root.group("Vendor", Consumer { v ->
                    v.repeatable(DECLARED_MAX).indexField("VKey")
                    v.field("VKey", FieldKind.STRING)
                    v.numberField("MinMargin", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                    v.group("Product", Consumer { p ->
                        p.repeatable(DECLARED_MAX).indexField("PKey")
                        p.field("PKey", FieldKind.STRING)
                        p.numberField("BasePrice", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                        p.group("Offer", Consumer { o ->
                            o.repeatable(DECLARED_MAX).indexField("OKey")
                            o.field("OKey", FieldKind.STRING)
                            o.numberField("Discount", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                            o.group("Tier", Consumer { t ->
                                t.repeatable(DECLARED_MAX).indexField("TKey")
                                t.field("TKey", FieldKind.STRING)
                                t.numberField("Qty", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                                t.numberField("UnitPrice", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                            })
                        })
                    })
                })
            }).build().toJson()
    }

    private val deep: EditSession by lazy { EditSession.read(deepJson) }

    private val deepFields = listOf(
        FieldDef("$VENDOR/VKey", Kind.STRING), FieldDef("$VENDOR/MinMargin", Kind.NUMBER, scale = 0),
        FieldDef("$PRODUCT/PKey", Kind.STRING), FieldDef("$PRODUCT/BasePrice", Kind.NUMBER, scale = 0),
        FieldDef("$OFFER/OKey", Kind.STRING), FieldDef("$OFFER/Discount", Kind.NUMBER, scale = 0),
        FieldDef("$TIER/TKey", Kind.STRING), FieldDef("$TIER/Qty", Kind.NUMBER, scale = 0),
        FieldDef("$TIER/UnitPrice", Kind.NUMBER, scale = 0),
    )

    /** The rich rule set — exercises a per-level comparison, cross-level refs 1 AND 3 parents up (resolved by
     *  common-prefix walk-up), a `Having`-filtered Sum, and a deep 3-level nested-star Sum. */
    private fun deepRules(): List<Rule> {
        val tier = deep.group(TIER)
        val offer = deep.group(OFFER)
        val vendor = deep.group(VENDOR)
        val up = tier.num("UnitPrice")
        return listOf(
            tier.errorRule("TUP", up.field(), up.filled().and(up.gt(1000))),                                  // per-level
            tier.errorRule("TOD", up.field(), up.filled().and(up.gt(offer.num("Discount")))),                 // 1 parent up
            tier.errorRule("TVM", up.field(), up.filled().and(up.gt(vendor.num("MinMargin")))),               // 3 parents up
            offer.errorRule("OHS", offer.num("Tier/Qty").field(),                                             // Having-filtered Sum
                Dsl.sum(offer.flatten("Tier*/Qty").having(tier.num("UnitPrice").gt(5))).gt(100)),
            vendor.errorRule("VNS", vendor.num("Product/Offer/Tier/Qty").field(),                             // deep nested-star Sum
                Dsl.sum(vendor.flatten("Product*/Offer*/Tier*/Qty")).gt(100000)),
        )
    }

    /**
     * **Deep + rich**: a 4-level nested DM with cross-level / `Having` / nested-star rules — the "sensible rules
     * across a deep document" benchmark. [firing] picks the regime: low-firing (valid doc — every rule passes)
     * or firing (a tier `UnitPrice` above both the parent `Offer/Discount` AND the 3-levels-up `Vendor/MinMargin`
     * → the two cross-level rules fire on every tier, exercising the walk-up resolution non-trivially while the
     * aggregates evaluate to a passing result — so interp==kernel is a real, multi-construct correctness check).
     * Scale knob N = Vendor count; each vendor fans out to 2 products × 2 offers × 3 tiers = 12 tiers.
     */
    fun deepRich(firing: Boolean = false): PerfScenario {
        val rules = deepRules()
        val kernelModel = rules.fold(deepJson) { json, r -> TypedRules.inject(json, r) }
        val interpModel = ModelDef(
            deepFields, rules.map { TypedRules.def(it) },
            repeatableGroups = setOf(VENDOR, PRODUCT, OFFER, TIER),
            declaredReps = mapOf(VENDOR to DECLARED_MAX, PRODUCT to DECLARED_MAX, OFFER to DECLARED_MAX, TIER to DECLARED_MAX),
            indexFields = setOf("$VENDOR/VKey", "$PRODUCT/PKey", "$OFFER/OKey", "$TIER/TKey"),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("deepRich(4-level${if (firing) "" else ",low-firing"})", kernelModel, interpModel) { sink, n, _ ->
            gen.populate(sink, rowsOf = deepRows(n), value = deepValues(firing))
        }
    }

    private fun deepRows(n: Int): RowsFn = { g, _, _ -> when (g) { VENDOR -> n; PRODUCT -> 2; OFFER -> 2; TIER -> 3; else -> 0 } }

    private fun deepValues(firing: Boolean): ValueStrategy = ValueStrategy { field, reps, _ ->
        when (field.path.substringAfterLast("/")) {
            "VKey", "PKey", "OKey", "TKey" -> "K" + reps.joinToString("_")   // unique index key per row (required)
            "MinMargin" -> if (firing) "8" else "1000"
            "BasePrice" -> "100"
            "Discount" -> "5"
            "Qty" -> "1"
            "UnitPrice" -> if (firing) "10" else "1"   // firing: 10 > Discount(5) AND 10 > MinMargin(8) → TOD+TVM fire
            else -> "0"
        }
    }

    // ---- WIDE model: many sibling repeatable groups (breadth axis) — each its own index + field + rule. -----
    /** A model with [numGroups] sibling repeatable groups `G0..G{n-1}` under the root, each with an index key
     *  and one NUMBER field + a per-row rule on it. Scales the group COUNT (breadth) at fixed rows-per-group —
     *  stresses any per-group setup that scans the whole model (would be O(groups²)); the interpreter's
     *  per-group machinery (`contextsFor`, attach-depth maps) is memoized per group, so it should stay linear. */
    fun wideGroups(numGroups: Int): PerfScenario {
        val wideJson = DocumentModelDraft.named("wide").locale("en_US")
            .rootGroup("Wide", Consumer { root ->
                for (i in 0 until numGroups) root.group("G$i", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("K$i")
                    g.field("K$i", FieldKind.STRING)
                    g.numberField("V$i", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                })
            }).build().toJson()
        val wide = EditSession.read(wideJson)
        val rules = (0 until numGroups).map { i ->
            val g = wide.group("/Wide/G$i")
            val v = g.num("V$i")
            g.errorRule("R$i", v.field(), v.filled().and(v.gt(1000)))
        }
        val kernelModel = rules.fold(wideJson) { json, r -> TypedRules.inject(json, r) }
        val fields = buildList {
            for (i in 0 until numGroups) { add(FieldDef("/Wide/G$i/K$i", Kind.STRING)); add(FieldDef("/Wide/G$i/V$i", Kind.NUMBER, scale = 0)) }
        }
        val groups = (0 until numGroups).map { "/Wide/G$it" }.toSet()
        val interpModel = ModelDef(
            fields, rules.map { TypedRules.def(it) },
            repeatableGroups = groups, declaredReps = groups.associateWith { DECLARED_MAX },
            indexFields = (0 until numGroups).map { "/Wide/G$it/K$it" }.toSet(),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("wideGroups($numGroups)", kernelModel, interpModel) { sink, n, _ ->
            gen.populate(sink, rowsOf = { g, _, _ -> if (g in groups) n else 0 }, value = ValueStrategy { field, reps, _ ->
                if (field.path.substringAfterLast("/").startsWith("K")) "K" + reps.joinToString("_") else "1"   // V=1 → all pass
            })
        }
    }

    // ---- CONSTRAINED fields (IF20): a declared min/max NUMBER constraint checked per row in the formal pass. ----
    /** A repeatable Line group with a NUMBER field declaring `minValue`/`maxValue`, filled with VALID values, over
     *  N rows. The IF20 per-cell constraint check (range compare) runs once per filled cell inside the existing
     *  single-pass formal loop — so it must stay O(rows), NOT introduce a per-row rescan. The scale knob N = rows.
     *  (This is the perf half of IF20's per-cell family — measured, not asserted; uniqueValue would be the O(N²)
     *  risk and is deferred precisely because it needs the canonical-key treatment, not this per-cell path.) */
    fun constrainedFields(): PerfScenario {
        val kernelModel = DocumentModelDraft.named("constrained").locale("en_US")
            .rootGroup("C", Consumer { root ->
                root.group("Lines", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("K")
                    g.field("K", FieldKind.STRING)
                    g.numberField("V", Consumer<NumberFieldBuilder> { it.fractionDigits(0).min("1").max("1000000") })
                })
            }).build().toJson()
        val fields = listOf(
            FieldDef("/C/Lines/K", Kind.STRING),
            FieldDef("/C/Lines/V", Kind.NUMBER, scale = 0, minValue = "1", maxValue = "1000000"),
        )
        val interpModel = ModelDef(
            fields, emptyList(),
            repeatableGroups = setOf("/C/Lines"), declaredReps = mapOf("/C/Lines" to DECLARED_MAX),
            indexFields = setOf("/C/Lines/K"),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("constrained fields", kernelModel, interpModel) { sink, n, _ ->
            gen.populate(sink, rowsOf = { g, _, _ -> if (g == "/C/Lines") n else 0 }, value = ValueStrategy { field, reps, _ ->
                if (field.path.endsWith("/K")) "K" + reps.joinToString("_") else ((reps[1] % 1000) + 1).toString()   // V in 1..1000 → all valid
            })
        }
    }

    // ---- COMBINED real-world worst-case (deep+wide+rule-heavy+data-axis) — built with the dmkits THEMSELVES
    //      (modelkit DocumentModelDraft + rulekit typed DSL combinators), so the worst case dogfoods the typed
    //      builder. The model/rules/mirroring-combinators live in [ComplexRealWorld] (kept out of this file). --
    /** The "full" complex shape — ≈1000 fields / ≈1000 rules (the user's real-world target). */
    fun complexRealWorldFull(firing: Boolean = false): PerfScenario = ComplexRealWorld.scenario(ComplexRealWorld.FULL, firing)

    /** A reduced complex shape for the CI gate (fast kernel codegen + correctness check). */
    fun complexRealWorldGate(firing: Boolean = false): PerfScenario = ComplexRealWorld.scenario(ComplexRealWorld.GATE, firing)

    // B7's value strategy: like [perfValues] but leaves the COMPUTED field (Amount) EMPTY in the seed — so the
    // kernel's compute() reports every Line's Amount as a change (empty → Sum), matching the interpreter which
    // computes every row. Cost is filled (the aggregate operand); index fields filled (required).
    private fun b7Values(variant: Int): ValueStrategy = ValueStrategy { field, reps, _ ->
        when (field.path) {
            AMOUNT -> null                                              // computed field — leave empty
            COST -> ((reps[1] * reps[2] + variant) % 90 + 1).toString() // 1..90, always > 0
            QTY -> "1"
            LINE_KEY, SUB_KEY -> "K" + reps.joinToString("_")
            else -> "0"
        }
    }

    /** B7 — `compute()` at scale: a per-Line nested-aggregate computation `Amount := Sum(Subs* / Cost)`. Exercises
     *  the compute path (not validate) over nested groups; the computed field is empty in the seed so every Line
     *  computes a value. The scale knob N is the Line count (× [SUBS_PER_LINE] Sub operands each). */
    fun b7(): PerfScenario {
        val comp = lines.computation(amount) {
            it.name("AmountComp").calc(Dsl.sum(lines.flatten("Subs*/Cost")).expression()).message(Dsl.en("x")).build()
        }
        val kernelModel = TypedRules.inject(perfJson, comp)
        val interpModel = ModelDef(
            fields, emptyList(),
            computations = listOf(TypedRules.def(comp)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("B7 compute", kernelModel, interpModel, PerfOp.COMPUTE) { sink, n, variant ->
            gen.populate(sink, rowsOf = nestedRows(n), value = b7Values(variant))
        }
    }

    /** Computation regime lock: a stale value is cleared when the common guard does not hold. */
    fun computeCleared(): PerfScenario {
        val comp = lines.computation(amount) {
            it.name("AmountCleared")
                .commonPrecondition(qty.gt(1_000))
                .calc(NumberLiteral.of("1"))
                .message(Dsl.en("x")).build()
        }
        return computationOutcomeScenario("compute CLEARED", comp) { field, reps, _ ->
            when (field.path) {
                AMOUNT -> "7.00"
                QTY -> "1"
                LINE_KEY -> "K" + reps.joinToString("_")
                else -> null
            }
        }
    }

    /** Computation regime lock: every product exceeds the kernel's 15-significant-digit stored-number form. */
    fun computeErrored(): PerfScenario {
        val operation = qty.asNumber().times(Dsl.number("999999999999999")).expression()
        val comp = lines.computation(amount) {
            it.name("AmountErrored").calc(operation).message(Dsl.en("x")).build()
        }
        return computationOutcomeScenario("compute ERRORED", comp) { field, reps, _ ->
            when (field.path) {
                AMOUNT -> null
                QTY -> "99"
                LINE_KEY -> "K" + reps.joinToString("_")
                else -> null
            }
        }
    }

    private fun computationOutcomeScenario(
        name: String,
        computation: Computation,
        values: ValueStrategy,
    ): PerfScenario {
        val kernelModel = TypedRules.inject(perfJson, computation)
        val interpModel = ModelDef(
            fields,
            emptyList(),
            computations = listOf(TypedRules.def(computation)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val generator = SeedGenerator(interpModel)
        return PerfScenario(name, kernelModel, interpModel, PerfOp.COMPUTE) { sink, n, _ ->
            generator.populate(sink, rowsOf = { group, _, _ -> if (group == LINES) n else 0 }, value = values)
        }
    }

    /**
     * **Tiered-computation compute() at scale (IF21)** — a per-Line `Amount` selected by a `precondition` over
     * `Qty`: `[Qty] < 100 → [Qty]` else `[Qty] >= 100 → 0`. The compute path evaluates the precondition AND
     * selects the operation **per Line row**, so this stresses the tiered selection at scale; it must stay
     * LINEAR (O(rows · precondition), no per-row rescan) — the perf twin of the IF21 correctness fix. Measured
     * interp-vs-kernel (the kernel compiles the same tiered alternatives). Scale knob N = the Line count;
     * Subs are not populated (the computation reads only the Line-level `Qty`). Qty straddles the two tiers.
     */
    fun tieredComputation(): PerfScenario {
        // The operations are scale-compatible with Amount(scale 2): 9.99 matches and capable literal 0 may pad.
        // A scale-0 FIELD operand lacks that capability and would trip MVK_INVALID_COMPARE_DEC_PLACES. The
        // scale-exempt Qty ordering precondition is the per-row work this benchmark stresses.
        val comp = lines.computation(amount) {
            it.name("TierComp")
                .`when`(qty.lt(100)).calc(NumberLiteral.of("9.99"))   // [Qty] < 100 → 9.99
                .`when`(qty.ge(100)).calc(NumberLiteral.of("0"))      // [Qty] >= 100 → 0
                .message(Dsl.en("x")).build()
        }
        val kernelModel = TypedRules.inject(perfJson, comp)
        val interpModel = ModelDef(
            fields, emptyList(),
            computations = listOf(TypedRules.def(comp)),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("tiered compute", kernelModel, interpModel, PerfOp.COMPUTE) { sink, n, variant ->
            gen.populate(sink, rowsOf = { g, _, _ -> if (g == LINES) n else 0 }, value = tieredValues(variant))
        }
    }

    /** Tiered-compute value strategy: Amount empty (computed), LineKey filled (required index), Qty straddles
     *  the `< 100` / `>= 100` tiers so both alternatives are exercised across rows. */
    private fun tieredValues(variant: Int): ValueStrategy = ValueStrategy { field, reps, _ ->
        when (field.path) {
            AMOUNT -> null
            QTY -> ((reps[1] * 7 + variant) % 200).toString()   // 0..199 → straddles the two tiers
            LINE_KEY -> "K" + reps.joinToString("_")
            else -> "0"
        }
    }

    /**
     * **The semantic-index cliff** — a per-Line rule that resolves a semantic index `[/Perf/Lines/Amount For
     * "NOMATCH"]`. BEFORE IF116 the interpreter's `indexedCell` did a **linear scan of the repetition list per
     * lookup** (a non-matching key → the *full* scan, the worst case) and the rule iterates per row → **O(rows²)**;
     * IF116 memoizes the indexed-read column per (column, scope) at the document level (the kernel's
     * `IndexFieldCache` analog), so each lookup is O(1)/O(log) → O(rows). This scenario +
     * `PerfGateTest.semanticIndexStaysSubQuadratic` guard that fix against a regression to the per-lookup scan.
     * (A constant **non-matching** literal key is used deliberately — it forces the FULL row scan every call,
     * the worst case, which is what stresses the index. Both the literal and the field-keyed `For <field>` form
     * are supported; see SemanticIndexDiffTest / SemanticIndexFieldKeyedDiffTest.)
     */
    fun semanticIndexPerRow(): PerfScenario =
        scenario("SI cliff",
            lines.errorRule("SI", amount.field(), amount.filled().and(amount.forIndex("NOMATCH").gt(0))), ::flatRows)

    // ---- compute-overlay derived-view scenarios (IF143) — a cascade makes the OverlayContext ACTIVE, then a
    //      per-Row computation reads a derived view (index-column / $-correlation buckets) once PER OUTER ROW.
    //      The raw Document caches those views per (column, scope) at the document level, but the cascade overlay
    //      bypassed that cache and rebuilt from the wrapped cells on every read → O(rows²). `[A] := [Src]` is the
    //      overlay activator (a prior cascade value), so the reader below runs through the OverlayContext (an
    //      empty overlay would use the raw cached view — no rebuild, no cliff). The per-step overlay-view cache
    //      (IF143) restores O(rows). Both readers over a STORED column (Key/Src), NOT the step's own target, so the
    //      view is stable within the step (the self-target guard's cacheable side). Scale knob N = Row count.
    private const val OVL_ROW = "/Ovl/Row"
    private const val OVL_KEY = "$OVL_ROW/Key"
    private const val OVL_SRC = "$OVL_ROW/Src"
    private const val OVL_A = "$OVL_ROW/A"
    private const val OVL_B = "$OVL_ROW/B"
    private const val OVL_C = "$OVL_ROW/C"

    private val ovlJson: String by lazy {
        DocumentModelDraft.named("ovl").locale("en_US")
            .rootGroup("Ovl", Consumer { root ->
                root.group("Row", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("Key")
                    g.field("Key", FieldKind.STRING)
                    for (f in listOf("Src", "A", "B", "C")) g.numberField(f, Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                })
            }).build().toJson()
    }
    private val ovl: EditSession by lazy { EditSession.read(ovlJson) }
    private val ovlFields = listOf(
        FieldDef(OVL_KEY, Kind.STRING),
        FieldDef(OVL_SRC, Kind.NUMBER, scale = 0), FieldDef(OVL_A, Kind.NUMBER, scale = 0),
        FieldDef(OVL_B, Kind.NUMBER, scale = 0), FieldDef(OVL_C, Kind.NUMBER, scale = 0),
    )

    /** The overlay-ACTIVATING copy `[A] := [Src]` — a prior cascade value so the per-Row derived-view reader runs
     *  through the [OverlayContext] (an empty overlay uses the raw cached view — no rebuild, no reproducer). */
    private fun ovlActivator(rowEd: GroupEditor): Computation =
        rowEd.computation(rowEd.num("A")) { it.name("Acopy").calc(rowEd.num("Src").value()).message(Dsl.en("x")).build() }

    private fun ovlScenario(name: String, rowEd: GroupEditor, reader: Computation): PerfScenario {
        val comps = listOf(ovlActivator(rowEd), reader)
        val kernelModel = comps.fold(ovlJson) { json, c -> TypedRules.inject(json, c) }
        val interpModel = ModelDef(
            ovlFields, emptyList(), computations = comps.map { TypedRules.def(it) },
            repeatableGroups = setOf(OVL_ROW), declaredReps = mapOf(OVL_ROW to DECLARED_MAX), indexFields = setOf(OVL_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario(name, kernelModel, interpModel, PerfOp.COMPUTE) { sink, n, variant ->
            gen.populate(sink, rowsOf = { g, _, _ -> if (g == OVL_ROW) n else 0 }, value = ovlValues(variant))
        }
    }

    /** Row values: a unique required index Key, a stored `Src` whose values COLLIDE (mod 50, so the `$`-correlation
     *  buckets hold many rows), and the computed A/B/C empty in the seed (so every Row computes a value). */
    private fun ovlValues(variant: Int): ValueStrategy = ValueStrategy { field, reps, _ ->
        when (field.path) {
            OVL_KEY -> "K" + reps.joinToString("_")
            OVL_SRC -> ((reps[1] + variant) % 50).toString()
            OVL_A, OVL_B, OVL_C -> null
            else -> "0"
        }
    }

    /** **CO index (IF143)** — a per-Row `[B] := [Src For "K1"]` semantic-index read under an ACTIVE cascade overlay:
     *  `computeGuardedIndexCell` reads `indexColumn(Key)` per outer Row (the IF115 whole-column validity gate),
     *  which the [OverlayContext] rebuilt per read (O(rows²)) until the per-step overlay-view cache shares it. */
    fun computeOverlaySemanticIndex(): PerfScenario {
        val rowEd = ovl.group(OVL_ROW)
        val reader = rowEd.computation(rowEd.num("B")) {
            it.name("Bidx").calc(rowEd.num("Src").forIndex("K1").expression()).message(Dsl.en("x")).build()
        }
        return ovlScenario("CO index", rowEd, reader)
    }

    /** **CO correlation (IF143)** — a per-Row `$`-equality self-correlation precondition under an ACTIVE cascade
     *  overlay: `hashJoinTally` reads `correlationBuckets(Row, [Src], Src)` per outer Row, which the [OverlayContext]
     *  rebuilt (re-wrapping every row) per read until the per-step overlay-view cache shares it. `[C] := [Src]`
     *  when another Row shares this Row's `Src`. */
    fun computeOverlayCorrelation(): PerfScenario {
        val rowEd = ovl.group(OVL_ROW)
        val inRef = rowEd.num("Src")
        val corr = Dsl.atLeastOneFieldFilled(ovl.group("/Ovl").flatten("Row*/Src").having(
            Dsl.currentRepetition(rowEd.ref()).ne(Dsl.currentRepetition(rowEd.ref(), true))
                .and(inRef.eq(inRef.outer()))))
        val reader = rowEd.computation(rowEd.num("C")) {
            it.name("Ccorr").`when`(corr).calc(inRef.value()).message(Dsl.en("x")).build()
        }
        return ovlScenario("CO correlation", rowEd, reader)
    }

    private const val PA = "/Plan/A"
    private const val PB = "/Plan/B"
    private val parallelJson: String by lazy {
        DocumentModelDraft.named("plan").locale("en_US")
            .rootGroup("Plan", Consumer { root ->
                root.group("A", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("Dept")
                    g.field("Dept", FieldKind.STRING)
                    g.numberField("Va", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                })
                root.group("B", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("Dept")
                    g.field("Dept", FieldKind.STRING)
                    g.numberField("Vb", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                })
            })
            .build().toJson()
    }
    private val parallelSession: EditSession by lazy { EditSession.read(parallelJson) }
    private val parallelFields = listOf(
        FieldDef("$PA/Dept", Kind.STRING), FieldDef("$PA/Va", Kind.NUMBER, scale = 0),
        FieldDef("$PB/Dept", Kind.STRING), FieldDef("$PB/Vb", Kind.NUMBER, scale = 0),
    )

    // The parallel scenario's values: Dept is unique per group (`D<rowIndex>`) and OVERLAPPING across groups, so
    // A and B share keys `D1..DN` — a full matched outer join (N joined keys). ~half the A rows leave Va empty
    // (→ that key is "not collectively filled" → fires); B's Vb is always filled. [variant] shifts the parity.
    private fun parallelValues(variant: Int): ValueStrategy = ValueStrategy { f, reps, _ ->
        when (f.path) {
            "$PA/Dept", "$PB/Dept" -> "D" + reps[reps.size - 2]
            "$PA/Va" -> if ((reps[reps.size - 2] + variant) % 2 == 0) "1" else null
            "$PB/Vb" -> "1"
            else -> "0"
        }
    }

    /**
     * **Parallel iteration at scale** — two sibling repeatable groups `A` ⊥ `B` joined by the shared `Dept`
     * index, evaluated as a per-shared-key outer join (`FieldsNotCollectivelyFilled(A/Va, B/Vb)`). The scale
     * knob N is the rows-per-group; the keys fully overlap (`D1..DN`), so the join does N matched pairings — the
     * shape the kernel's `IndexFieldCache` makes O(rows) and where a naive nested-loop join would be O(rows²).
     */
    fun parallel(): PerfScenario {
        val va = parallelSession.group(PA).field("Va")
        val vb = parallelSession.group(PB).field("Vb")
        val rule = parallelSession.group("/Plan").errorRule("PI", va, Dsl.fieldsNotCollectivelyFilled(va, vb))
        val kernelModel = TypedRules.inject(parallelJson, rule)
        val interpModel = ModelDef(
            parallelFields, listOf(TypedRules.def(rule)),
            repeatableGroups = setOf(PA, PB),
            declaredReps = mapOf(PA to DECLARED_MAX, PB to DECLARED_MAX),
            indexFields = setOf("$PA/Dept", "$PB/Dept"),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("PI parallel", kernelModel, interpModel) { sink, n, variant ->
            gen.populate(sink, rowsOf = { g, _, _ -> if (g == PA || g == PB) n else 0 }, value = parallelValues(variant))
        }
    }

    // ---- framed parallel iteration (a repeatable frame X above one joined group) — the O(frames × clean-rows) trap
    private const val FX = "/Plan/X"
    private const val FXA = "/Plan/X/A"
    private const val FB = "/Plan/B"
    private val framedJson: String by lazy {
        DocumentModelDraft.named("plan").locale("en_US")
            .rootGroup("Plan", Consumer { root ->
                root.group("X", Consumer { x ->                       // a repeatable, NON-indexed frame
                    x.repeatable(DECLARED_MAX)
                    x.group("A", Consumer { g ->
                        g.repeatable(DECLARED_MAX).indexField("Dept")
                        g.field("Dept", FieldKind.STRING)
                        g.numberField("Va", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                    })
                })
                root.group("B", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("Dept")
                    g.field("Dept", FieldKind.STRING)
                    g.numberField("Vb", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                })
            })
            .build().toJson()
    }
    private val framedSession: EditSession by lazy { EditSession.read(framedJson) }
    private val framedFields = listOf(
        FieldDef("$FXA/Dept", Kind.STRING), FieldDef("$FXA/Va", Kind.NUMBER, scale = 0),
        FieldDef("$FB/Dept", Kind.STRING), FieldDef("$FB/Vb", Kind.NUMBER, scale = 0),
    )

    private const val FRAMED_KEYS = 3   // the shared clean group B has a BOUNDED key set (see parallelFramed)

    /**
     * **Framed parallel iteration — frame scaling** (`/Plan/X/A ⋈ B`, X a repeatable frame). The scale knob N is
     * the **frame-row count** (X), with one A row per X row; the shared clean group B has a small BOUNDED key
     * set ([FRAMED_KEYS]) that the A keys cycle through (so each frame row's join matches). This stays **O(N)**
     * and exercises the **per-common-parent-row hoist** of B's index map (the kernel's `IndexFieldCache` analog —
     * built once, not rebuilt per frame row). *(Scaling B too would be O(frame × B) for BOTH engines — the
     * framed shape's inherent cost, not a cliff — so only the frame scales here.)*
     */
    fun parallelFramed(): PerfScenario {
        val xaVa = framedSession.group(FXA).num("Va")
        val bVb = framedSession.group(FB).num("Vb")
        val rule = framedSession.group("/Plan").errorRule("PF", xaVa.field(),
            xaVa.filled().and(bVb.filled()).and(xaVa.gt(bVb)))
        val kernelModel = TypedRules.inject(framedJson, rule)
        val interpModel = ModelDef(
            framedFields, listOf(TypedRules.def(rule)),
            repeatableGroups = setOf(FX, FXA, FB),
            declaredReps = mapOf(FX to DECLARED_MAX, FXA to DECLARED_MAX, FB to DECLARED_MAX),
            indexFields = setOf("$FXA/Dept", "$FB/Dept"),
        )
        val gen = SeedGenerator(interpModel)
        val values = ValueStrategy { f, reps, _ ->
            when (f.path) {
                "$FXA/Dept" -> "D" + (1 + reps[reps.size - 3] % FRAMED_KEYS)  // A's key cycles through B's bounded keys
                "$FB/Dept" -> "D" + reps[reps.size - 2]                       // B's keys: D1..D{FRAMED_KEYS}
                "$FXA/Va" -> "9"
                "$FB/Vb" -> "5"
                else -> "0"
            }
        }
        return PerfScenario("PF framed", kernelModel, interpModel) { sink, n, _ ->
            gen.populate(sink, rowsOf = { g, _, _ -> when (g) { FX -> n; FB -> FRAMED_KEYS; FXA -> 1; else -> 0 } }, value = values)
        }
    }

    // ---- Europe/Berlin DATE_TIME (IF132): exercises the `berlinOffsetSeconds` hot path at scale. Every DATE_TIME
    //      conversion under a non-UTC model runs the civil-calendar offset math (IF128/IF131); a per-row
    //      `DifferenceInHours` rule over a large repetition list makes that cost measurable. The values are
    //      mid-June (constant CEST — NOT DST-straddling), so Berlin and UTC agree on the DIFFERENCE and
    //      interp == kernel holds bit-for-bit, while the offset path still runs per conversion (the cost under test).
    private const val DT_RUNS = "/Sched/Runs"
    private const val DT_KEY = "$DT_RUNS/RunKey"
    private const val DT_SA = "$DT_RUNS/StartedAt"
    private const val DT_CA = "$DT_RUNS/EndedAt"
    private const val DT_FMT = "yyyy-MM-dd'T'HH:mm:ss"

    /** DocumentModelDraft defaults `modelConfig.timeZone` to `Europe/Berlin`; the UTC control swaps it. */
    private val dtBerlinJson: String by lazy {
        DocumentModelDraft.named("sched").locale("en_US")
            .rootGroup("Sched", Consumer { root ->
                root.group("Runs", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("RunKey")
                    g.field("RunKey", FieldKind.STRING)
                    g.dateTimeField("StartedAt", Consumer<DateTimeFieldBuilder> { })
                    g.dateTimeField("EndedAt", Consumer<DateTimeFieldBuilder> { })
                })
            }).build().toJson()
    }
    private val dtUtcJson: String by lazy { dtBerlinJson.replace("\"Europe/Berlin\"", "\"UTC\"") }

    /** Non-DST-straddling Berlin datetimes: StartedAt in mid-June (constant CEST), EndedAt = +3 h same day, so
     *  `DifferenceInHours(StartedAt, EndedAt)` = 3 under BOTH zones (interp == kernel) while the offset math runs. */
    private fun dateTimeValues(variant: Int): ValueStrategy = ValueStrategy { field, reps, _ ->
        val row = reps[1]
        val day = 1 + (row + variant) % 28
        val hour = (row + variant) % 18
        when (field.path) {
            DT_KEY -> "K" + reps.joinToString("_")
            DT_SA -> "2024-06-%02dT%02d:00:00".format(day, hour)
            DT_CA -> "2024-06-%02dT%02d:00:00".format(day, hour + 3)
            else -> "0"
        }
    }

    private fun dateTimeScenario(name: String, json: String, zone: ModelTimeZone): PerfScenario {
        val g = EditSession.read(json).group(DT_RUNS)
        val sa = g.dateTime("StartedAt")
        val ca = g.dateTime("EndedAt")
        val rule = g.errorRule("DTH", sa.field(), sa.filled().and(Dsl.differenceInHours(sa, ca).gt(0)))
        val kernelModel = TypedRules.inject(json, rule)
        val fields = listOf(
            FieldDef(DT_KEY, Kind.STRING),
            FieldDef(DT_SA, Kind.DATE_TIME, format = DT_FMT),
            FieldDef(DT_CA, Kind.DATE_TIME, format = DT_FMT),
        )
        val interpModel = ModelDef(
            fields, listOf(TypedRules.def(rule)),
            repeatableGroups = setOf(DT_RUNS), declaredReps = mapOf(DT_RUNS to DECLARED_MAX),
            indexFields = setOf(DT_KEY), timeZone = zone,
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario(name, kernelModel, interpModel) { sink, n, variant ->
            gen.populate(sink, rowsOf = { grp, _, _ -> if (grp == DT_RUNS) n else 0 }, value = dateTimeValues(variant))
        }
    }

    /** **DT Berlin (IF132)** — a per-row `DifferenceInHours` over `Europe/Berlin` DATE_TIME fields: the datetime
     *  instant math (`berlinOffsetSeconds` civil-calendar path, IF128/IF131) run once per conversion, at scale. */
    fun berlinDateTime(): PerfScenario = dateTimeScenario("DT Berlin", dtBerlinJson, ModelTimeZone.EUROPE_BERLIN)

    /** The **UTC control** for [berlinDateTime]: identical shape with the zone axis inert, so the Berlin/UTC delta
     *  isolates the DST offset cost. */
    fun utcDateTimeControl(): PerfScenario = dateTimeScenario("DT UTC control", dtUtcJson, ModelTimeZone.UTC)

    // ---- Europe/Berlin wall-day AddDays: every row lands in an ordinary hour, a spring gap, or a fall
    //      overlap in both directions. The historic cases cross the 1945 CEST↔CEMT transitions, so all three
    //      supported Berlin offsets participate in the wall-day landing path.
    private const val AD_RUNS = "/Schedule/Runs"
    private const val AD_KEY = "$AD_RUNS/RunKey"
    private const val AD_START = "$AD_RUNS/StartedAt"
    private const val AD_ANCHOR = "$AD_RUNS/AnchorAt"
    private const val AD_DAYS = "$AD_RUNS/Days"
    private const val AD_EXPECTED = "$AD_RUNS/ExpectedMinutes"

    private data class AddDaysCase(
        val startedAt: String,
        val days: String,
        val anchorAt: String,
        val expectedMinutes: String,
    )

    private val addDaysCases = listOf(
        AddDaysCase("2024-06-10T08:15:00", "1", "2024-06-11T08:15:00", "0"),
        AddDaysCase("2024-06-10T08:15:00", "-1", "2024-06-09T08:15:00", "0"),
        AddDaysCase("2024-03-30T02:30:00", "1", "2024-03-31T03:30:00", "60"),
        AddDaysCase("2024-04-01T02:30:00", "-1", "2024-03-31T03:30:00", "0"),
        AddDaysCase("2024-10-26T02:30:00", "1", "2024-10-27T03:30:00", "120"),
        AddDaysCase("2024-10-28T02:30:00", "-1", "2024-10-27T03:30:00", "60"),
        AddDaysCase("1945-05-23T02:30:00", "1", "1945-05-24T03:30:00", "60"),
        AddDaysCase("1945-05-25T02:30:00", "-1", "1945-05-24T03:30:00", "0"),
        AddDaysCase("1945-09-23T02:30:00", "1", "1945-09-24T03:30:00", "120"),
        AddDaysCase("1945-09-25T02:30:00", "-1", "1945-09-24T03:30:00", "60"),
    )

    private val addDaysJson: String by lazy {
        DocumentModelDraft.named("scheduleAddDays").locale("en_US")
            .rootGroup("Schedule", Consumer { root ->
                root.group("Runs", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("RunKey")
                    g.field("RunKey", FieldKind.STRING)
                    g.dateTimeField("StartedAt", Consumer<DateTimeFieldBuilder> { })
                    g.dateTimeField("AnchorAt", Consumer<DateTimeFieldBuilder> { })
                    g.numberField("Days", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                    g.numberField("ExpectedMinutes", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                })
            }).build().toJson()
    }

    /**
     * A historical Berlin DATE_TIME `AddDays` scenario. Its repeated rows cover ordinary landings plus gap and
     * overlap landings in both directions, including the 1945 CEMT (+03:00) window. The typed condition keeps
     * the DATE_TIME result of `AddDays` and compares its instant-distance to the per-row expected value.
     */
    fun berlinAddDays(): PerfScenario {
        val runs = EditSession.read(addDaysJson).group(AD_RUNS)
        val started = runs.dateTime("StartedAt")
        val anchor = runs.dateTime("AnchorAt")
        val days = runs.num("Days")
        val expected = runs.num("ExpectedMinutes")
        val condition = Dsl.allFieldsFilled(started.field(), anchor.field(), days.field(), expected.field()).and(
            Dsl.differenceInMinutes(Dsl.addDays(started, days.asNumber()), anchor).eq(expected.asNumber()),
        )
        val rule = runs.errorRule("ADH", started.field(), condition)
        val kernelModel = TypedRules.inject(addDaysJson, rule)
        val fields = listOf(
            FieldDef(AD_KEY, Kind.STRING),
            FieldDef(AD_START, Kind.DATE_TIME, format = DT_FMT),
            FieldDef(AD_ANCHOR, Kind.DATE_TIME, format = DT_FMT),
            FieldDef(AD_DAYS, Kind.NUMBER, scale = 0),
            FieldDef(AD_EXPECTED, Kind.NUMBER, scale = 0),
        )
        val interpModel = ModelDef(
            fields,
            listOf(TypedRules.def(rule)),
            repeatableGroups = setOf(AD_RUNS),
            declaredReps = mapOf(AD_RUNS to DECLARED_MAX),
            indexFields = setOf(AD_KEY),
            timeZone = ModelTimeZone.EUROPE_BERLIN,
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("Berlin AddDays historical", kernelModel, interpModel) { sink, n, variant ->
            gen.populate(
                sink,
                rowsOf = { group, _, _ -> if (group == AD_RUNS) n else 0 },
                value = ValueStrategy { field, reps, _ ->
                    val c = addDaysCases[(reps[1] + variant - 1) % addDaysCases.size]
                    when (field.path) {
                        AD_KEY -> "K" + reps.joinToString("_")
                        AD_START -> c.startedAt
                        AD_ANCHOR -> c.anchorAt
                        AD_DAYS -> c.days
                        AD_EXPECTED -> c.expectedMinutes
                        else -> "0"
                    }
                },
            )
        }
    }

    /** Every catalog scenario, in order — for the gate to sweep. */
    fun all(): List<PerfScenario> = listOf(b1(), b2(), b3(), b4(), b5(), b6())

    // Unambiguously-malformed values (scale-independent) for the kinds the interpreter formal-checks. NUMBER:
    // invalid chars / signed-zero / leading-plus / leading-blank. DATE: garbage / a value in the WRONG format
    // for the field (ISO on a de field, de on an ISO field) / a CALENDAR-INVALID day (Feb 31 — a day past the
    // month's length, the gap the day≤28 ValidValues never surfaced) — each must produce the kernel's formal
    // code and suppress the referencing rule, identically in both engines. Other kinds stay valid.
    private fun malformed(field: io.github.mbackschat.a12.dm.interpreter.model.FieldDef, rnd: Random): String? = when (field.kind) {
        Kind.NUMBER -> listOf("abc", "-0", "+5", " 5 ").let { it[rnd.nextInt(it.size)] }
        Kind.DATE -> {
            val de = field.format == "dd.MM.yyyy"
            val wrongFormat = if (de) "2024-06-15" else "15.06.2024"
            val calendarInvalid = if (de) "31.02.2024" else "2024-02-31"   // Feb 31 → datumFormatFalsch
            listOf("not-a-date", wrongFormat, calendarInvalid).let { it[rnd.nextInt(it.size)] }
        }
        else -> null
    }

    // The fuzz uses its OWN model (`fuzz.dm.json`, own e-commerce domain) — a deliberately DIVERSE shape the
    // gate's perf model lacks: STRING + NUMBER (scale 0 and 2) + an ISO date + a NON-ISO (`dd.MM.yyyy`) date +
    // BOOLEAN, nested two levels. The non-ISO date is the high-value bit: it exercises locale-variant date
    // canonicalization under fuzz. Only supported operators, valid (referenced) error fields.
    private val fuzzJson = RuntimeLaws.model("models/fuzz.dm.json")
    private val fuzz = EditSession.read(fuzzJson)
    private const val FZ_LINES = "/Cart/Lines"   // the rule-target group paths
    private const val FZ_PARTS = "/Cart/Lines/Parts"
    private val cart = fuzz.group("/Cart")             // anchor for the /Cart-level star aggregates
    private val cartLines = fuzz.group(FZ_LINES)       // editor on the repeatable Lines group
    private val cartParts = fuzz.group(FZ_PARTS)       // editor on the nested repeatable Parts group
    // Typed, VALIDATED field handles by RELATIVE child name off the editors (fail-fast on a bad name/kind).
    // (.field() yields the underlying FieldRef where a field-LIST operator wants a FieldOperand.)
    private val fzItem = cartLines.str("ItemId")
    private val fzQty = cartLines.num("Qty")
    private val fzPrice = cartLines.num("Price")
    private val fzOrdered = cartLines.date("OrderedOn")
    private val fzDue = cartLines.date("DueBy")
    private val fzWeight = cartParts.num("Weight")
    private val nestedWeights = cartLines.flatten("Parts*/Weight")
    private val allWeights = cart.flatten("Lines*/Parts*/Weight")
    private val itemIds = cart.flatten("Lines*/ItemId")
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
    private fun fuzzRuleSpecs(): List<Rule> = listOf(
        cartLines.errorRule("HQ", fzQty.field(), fzQty.filled().and(fzQty.gt(50))),
        cartLines.errorRule("YO", fzOrdered.field(), fzOrdered.filled().and(Dsl.yearFromDate(fzOrdered).gt(2020))),
        cartLines.errorRule("YD", fzDue.field(), fzDue.filled().and(Dsl.yearFromDate(fzDue).gt(2020))),
        cart.errorRule("TP", fzPrice.field(), Dsl.sum(cart.flatten("Lines*/Price")).gt(1000)),
        cart.errorRule("FF", fzQty.field(), Dsl.firstFilledNumber(cart.flatten("Lines*/Qty")).gt(50)),   // first specified row, skip empties
        cart.errorRule("SP", fzQty.field(), Dsl.sumOfProducts(cart.flatten("Lines*/Qty"), cart.flatten("Lines*/Price")).gt(100000)),   // Σ qᵢ·pᵢ, row-paired
        cartLines.errorRule("NS", fzQty.field(), fzQty.filled().and(Dsl.sumOfProducts(nestedWeights, nestedWeights).gt(100000))), // Parts* is row-bound by the current Line
        cart.errorRule("MS", fzWeight.field(), Dsl.sum(allWeights).gt(1000)), // legal two-star extent
        // Starred VALUE-LIST quantifiers. The net had none at all, so the starred-extent gate they ride was
        // outside the differential entirely — which is why IF264's split from the all-rows aggregate gate was
        // invisible here. `NL` and `NX` are the extent-SENSITIVE pair (`No` on the fields side, `NotAll` on the
        // values side). The `K0`/`K1` pool makes both fire on some seeds and not others.
        // `AL` is the third of the family and the one that earned the net its keep: adding it turned 6 of 180
        // combinations red, in BOTH directions, on three independent partial-relevance defects none of the
        // other rules could reach — [IF266] (a relevance-blind index-duplicate relation), [IF267] (the scan
        // reading non-relevant cells) and [IF268] (the rule-run gate). It is the only rule here that reads
        // cells WITHOUT reading the extent, which is why it discriminates where `NL`/`NX` cannot.
        cart.errorRule("NL", fzItem.field(),
            Dsl.noFieldValueIncludedInValueList(listOf("K0", "K1"), itemIds)),
        cart.errorRule("AL", fzItem.field(),
            Dsl.atLeastOneFieldValueIncludedInValueList(listOf("K0", "K1"), itemIds)),
        cartLines.errorRule("NX", fzItem.field(),
            Dsl.notAllFieldValuesIncludedInValueList(listOf<FieldOperand>(itemIds), fzItem.field())),
        cartLines.errorRule("DV", fzPrice.field(), fzQty.filled().and(fzPrice.asNumber().dividedBy(fzQty.asNumber()).gt(5))),   // division (+ div-by-zero quiet)
        cartLines.errorRule("PW", fzQty.field(), fzQty.asNumber().toThePowerOf(Dsl.number("2")).gt(100)),                       // power
        cartLines.errorRule("AB", fzPrice.field(), Dsl.abs(fzPrice.asNumber()).gt(500)),                                        // |x|
        cartLines.errorRule("LN", fzItem.field(), fzItem.filled().and(fzItem.length().gt(1))),                                // string length (+ uniqueIndex suppression on dup keys)
        cartLines.errorRule("NV", fzQty.field(), fzQty.filled().and(Dsl.numberOfValueInFields(0L, fzQty.field(), fzPrice.field()).ge(1))), // count-of-value (In syntax; empty not counted)
        cartLines.errorRule("RS", fzItem.field(), fzItem.filled().and(Dsl.rangeAsString(fzItem.field(), 1, 2).eq("K0"))), // substring of the index pool
        cartLines.errorRule("RN", fzItem.field(), fzItem.filled().and(Dsl.rangeAsNumber(fzItem.field(), 2, 2).ge(1))),         // substring-as-number (\\d+ else 0)
        cartLines.errorRule("FS", fzQty.field(), fzQty.filled().and(Dsl.fieldValueAsString(fzQty.field()).eq("0"))),            // number→string raw-value coercion
        cartLines.errorRule("AF", fzQty.field(), Dsl.allFieldsFilled(fzQty.field(), fzPrice.field())),   // field-list quantifier (3VL on a malformed operand)
        cartLines.errorRule("FU", fzQty.field(), Dsl.fieldValuesNotUnique(fzQty.field(), fzPrice.field())),   // fires when Qty == Price numerically (cross-scale); empties skipped
        cartLines.errorRule("DT", fzQty.field(), fzQty.differsWithTolerance(Tolerance.RANGE_5, fzPrice)),   // |Qty − Price| > 5 (strict band; empty→0)

        cartLines.errorRule("RD", fzPrice.field(), fzPrice.filled().and(Dsl.roundDown(fzPrice.asNumber(), 0).gt(50))), // FLOOR (signed Price → negatives)
        cartLines.errorRule("RA", fzPrice.field(), fzPrice.filled().and(Dsl.roundAccounting(fzPrice.asNumber().times(fzQty.asNumber()), 1).gt(100))), // HALF_UP over a product
        // starred value/count aggregates (IF51 family) — random-combination coverage for the class that had the
        // IG29–IG32 cluster. `MN` (`MinValue < 5`) FIRES on the EMPTY identity (0 < 5), so it exercises the
        // content-bearing-empty-row path (an instantiated-but-field-empty row is evaluated — IF53); `MX`/`ND`/`NF`
        // don't fire on 0 (0 > 50 / 0 distinct >= 2 false), covering the non-firing side.
        cart.errorRule("MN", fzQty.field(), Dsl.minValue(cart.flatten("Lines*/Qty")).lt(5)),
        cart.errorRule("MX", fzQty.field(), Dsl.maxValue(cart.flatten("Lines*/Qty")).gt(50)),
        cart.errorRule("ND", fzPrice.field(), Dsl.numberOfDifferentValues(cart.flatten("Lines*/Price")).ge(2)),
        cart.errorRule("NF", fzQty.field(), Dsl.numberOfFilledFields(cart.flatten("Lines*/Qty")).ge(2)),
        cartParts.errorRule("HW", fzWeight.field(), fzWeight.filled().and(fzWeight.gt(50))),
        cartParts.errorRule("RW", fzWeight.field(), fzWeight.filled().and(Dsl.roundUpValue(fzWeight).gt(50))), // CEILING, *Value field form
        cart.errorRule("CR", fzQty.field(), fzQty.filled().and(Dsl.currentRepetition(cartLines.ref()).gt(2))), // per-row index
        cartParts.errorRule("XL", fzWeight.field(), fzWeight.filled().and(fzWeight.gt(fzQty))),
        // `$` OUTER-CORRELATION (IF47): "another Line shares this Line's Qty" — an equality self-correlation
        // (hash-join fast path), the construct whose absence hid IG23. Teaching the fuzz it (100 random docs ×
        // both engines) is the durable net for "the `$`-class can't happen again". The `FieldFilled(Qty)` gate
        // keeps the iterating rule from firing on an empty Line rep (else MVK_NEG_CONDITION_IN_ITERATION).
        cart.errorRule("DQ", fzQty.field(),
            fzQty.filled().and(Dsl.atLeastOneFieldFilled(cart.flatten("Lines*/Qty").having(
                Dsl.currentRepetition(cartLines.ref()).ne(Dsl.currentRepetition(cartLines.ref(), true))
                    .and(fzQty.eq(fzQty.outer())))))),
    )

    /** The fuzz rules' rendered condition texts — what [CatalogParityTest] scans to know which constructs the
     *  net covers (each [Rule] renders its own canonical, group-relative condition). */
    fun fuzzConditions(): List<String> = fuzzRuleSpecs().map { it.render() }

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
        val kernelModel = rules.fold(fuzzJson) { json, r -> TypedRules.inject(json, r) }
        val interpModel = ModelDef(
            fuzzFields, rules.map { TypedRules.def(it) },
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
