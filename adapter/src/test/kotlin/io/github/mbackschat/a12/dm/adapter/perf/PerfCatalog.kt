package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder
import java.util.function.Consumer
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

    /**
     * A parameterized many-rules scenario: [count] kernel-valid rules over the nested model, cycling per-row /
     * cross-field / nested / aggregate templates with varying thresholds (unique codes). The scale knob is the
     * **rule count**, used by the kernel-edge sweep to find where the interpreter's per-call re-parse (#31)
     * loses to the kernel's compile-once codegen — the kernel's documented architectural advantage.
     */
    fun manyRules(count: Int, firing: Boolean = true): PerfScenario {
        val rules = (0 until count).map { i ->
            val code = "R%03d".format(i)
            when (i % 4) {
                0 -> FuzzRule(LINES, code, "../Qty", "FieldFilled(Qty) And [Qty] > ${50 + i}")
                1 -> FuzzRule(LINES, code, "../Amount", "FieldFilled(Qty) And FieldFilled(Amount) And [Amount] > [Qty]")
                2 -> FuzzRule(SUBS, code, "../Cost", "FieldFilled(Cost) And [Cost] > ${40 + (i % 50)}")
                else -> FuzzRule("/Perf", code, "../Lines/Amount", "Sum(Lines*/Amount) > ${1000 + i}")
            }
        }
        val kernelModel = rules.fold(perfJson) { m, r -> RuntimeLaws.withRule(m, r.group, "R_${r.code}", r.errorRel, r.code, r.cond) }
        val interpModel = ModelDef(
            fields, rules.map { RuleDef("${it.group}/${it.code}", it.group, it.cond, it.code, it.errorRel) },
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
     * regime where the per-row eval (firedType + polarity) dominates — where the kernel's two-phase short-circuit
     * helps and ours doesn't (yet); the firing-heavy [perfValues] hides it behind message-building.
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
        val rules = (0 until count).map { i ->
            FuzzRule(LINES, "R%04d".format(i), "../F$i", "FieldFilled(F$i) And [F$i] > ${50 + i}")
        }
        val kernelModel = rules.fold(distributedModelJson(count)) { m, r ->
            RuntimeLaws.withRule(m, r.group, "R_${r.code}", r.errorRel, r.code, r.cond)
        }
        val distFields = buildList {
            add(FieldDef(LINE_KEY, Kind.STRING))
            for (i in 0 until count) add(FieldDef("/Perf/Lines/F$i", Kind.NUMBER))
        }
        val interpModel = ModelDef(
            distFields, rules.map { RuleDef("${it.group}/${it.code}", it.group, it.cond, it.code, it.errorRel) },
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

    private val deepFields = listOf(
        FieldDef("$VENDOR/VKey", Kind.STRING), FieldDef("$VENDOR/MinMargin", Kind.NUMBER, scale = 0),
        FieldDef("$PRODUCT/PKey", Kind.STRING), FieldDef("$PRODUCT/BasePrice", Kind.NUMBER, scale = 0),
        FieldDef("$OFFER/OKey", Kind.STRING), FieldDef("$OFFER/Discount", Kind.NUMBER, scale = 0),
        FieldDef("$TIER/TKey", Kind.STRING), FieldDef("$TIER/Qty", Kind.NUMBER, scale = 0),
        FieldDef("$TIER/UnitPrice", Kind.NUMBER, scale = 0),
    )

    /** The rich rule set — exercises a per-level comparison, cross-level refs 1 AND 3 parents up (resolved by
     *  common-prefix walk-up), a `Having`-filtered Sum, and a deep 3-level nested-star Sum. */
    private fun deepRules(): List<FuzzRule> = listOf(
        FuzzRule(TIER, "TUP", "../UnitPrice", "FieldFilled(UnitPrice) And [UnitPrice] > 1000"),                       // per-level
        FuzzRule(TIER, "TOD", "../UnitPrice", "FieldFilled(UnitPrice) And [UnitPrice] > [$OFFER/Discount]"),          // 1 parent up
        FuzzRule(TIER, "TVM", "../UnitPrice", "FieldFilled(UnitPrice) And [UnitPrice] > [$VENDOR/MinMargin]"),        // 3 parents up
        FuzzRule(OFFER, "OHS", "../Tier/Qty", "Sum(Tier*/Qty Having [Tier/UnitPrice] > 5) > 100"),                    // Having-filtered Sum
        FuzzRule(VENDOR, "VNS", "../Product/Offer/Tier/Qty", "Sum(Product*/Offer*/Tier*/Qty) > 100000"),              // deep nested-star Sum
    )

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
        val kernelModel = rules.fold(deepJson) { m, r -> RuntimeLaws.withRule(m, r.group, "R_${r.code}", r.errorRel, r.code, r.cond) }
        val interpModel = ModelDef(
            deepFields, rules.map { RuleDef("${it.group}/${it.code}", it.group, it.cond, it.code, it.errorRel) },
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
        val json = DocumentModelDraft.named("wide").locale("en_US")
            .rootGroup("Wide", Consumer { root ->
                for (i in 0 until numGroups) root.group("G$i", Consumer { g ->
                    g.repeatable(DECLARED_MAX).indexField("K$i")
                    g.field("K$i", FieldKind.STRING)
                    g.numberField("V$i", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
                })
            }).build().toJson()
        val rules = (0 until numGroups).map { i -> FuzzRule("/Wide/G$i", "R$i", "../V$i", "FieldFilled(V$i) And [V$i] > 1000") }
        val kernelModel = rules.fold(json) { m, r -> RuntimeLaws.withRule(m, r.group, "R_${r.code}", r.errorRel, r.code, r.cond) }
        val fields = buildList {
            for (i in 0 until numGroups) { add(FieldDef("/Wide/G$i/K$i", Kind.STRING)); add(FieldDef("/Wide/G$i/V$i", Kind.NUMBER, scale = 0)) }
        }
        val groups = (0 until numGroups).map { "/Wide/G$it" }.toSet()
        val interpModel = ModelDef(
            fields, rules.map { RuleDef("${it.group}/${it.code}", it.group, it.cond, it.code, it.errorRel) },
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
        val kernelModel = RuntimeLaws.withComputation(perfJson, LINES, "AmountComp", "../Amount", "Sum(Subs*/Cost)")
        val interpModel = ModelDef(
            fields, emptyList(),
            computations = listOf(ComputationDef(AMOUNT, LINES, "Sum(Subs*/Cost)")),
            repeatableGroups = setOf(LINES, SUBS),
            declaredReps = mapOf(LINES to DECLARED_MAX, SUBS to DECLARED_MAX),
            indexFields = setOf(LINE_KEY, SUB_KEY),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario("B7 compute", kernelModel, interpModel, PerfOp.COMPUTE) { sink, n, variant ->
            gen.populate(sink, rowsOf = nestedRows(n), value = b7Values(variant))
        }
    }

    /**
     * **The semantic-index cliff** — a per-Line rule that resolves a semantic index `[/Perf/Lines/Amount For
     * "NOMATCH"]`. The interpreter's `indexedCell` does a **linear scan of the repetition list per lookup**
     * (here a non-matching key → the *full* scan, the worst case), and the rule iterates per row → **O(rows²)**;
     * the kernel's `IndexFieldCache` makes each lookup O(1) → O(rows). This is the shape where the kernel's
     * runtime optimization decisively beats a naive interpreter (KernelEdgeTest.semanticIndexCliff verifies it).
     * (A constant **non-matching** literal key is used deliberately — it forces the FULL row scan every call,
     * the worst case, which is what stresses the index. Both the literal and the field-keyed `For <field>` form
     * are supported; see SemanticIndexDiffTest / SemanticIndexFieldKeyedDiffTest.)
     */
    fun semanticIndexPerRow(): PerfScenario =
        scenario("SI cliff", LINES, "SI", "../Amount",
            "FieldFilled(Amount) And [/Perf/Lines/Amount For \"NOMATCH\"] > 0", ::flatRows)

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
        val cond = "FieldsNotCollectivelyFilled(A/Va, B/Vb)"
        val kernelModel = RuntimeLaws.withRule(parallelJson, "/Plan", "PI", "../A/Va", "PI", cond)
        val interpModel = ModelDef(
            parallelFields, listOf(RuleDef("/Plan/PI", "/Plan", cond, "PI", "../A/Va")),
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
        val cond = "FieldFilled(X/A/Va) And FieldFilled(B/Vb) And [X/A/Va] > [B/Vb]"
        val kernelModel = RuntimeLaws.withRule(framedJson, "/Plan", "PF", "../X/A/Va", "PF", cond)
        val interpModel = ModelDef(
            framedFields, listOf(RuleDef("/Plan/PF", "/Plan", cond, "PF", "../X/A/Va")),
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

    /** Every catalog scenario, in order — for the gate to sweep. */
    fun all(): List<PerfScenario> = listOf(b1(), b2(), b3(), b4(), b5(), b6())

    private data class FuzzRule(val group: String, val code: String, val errorRel: String, val cond: String)

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
