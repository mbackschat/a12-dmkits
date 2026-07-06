package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.replayInto
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.asSink
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.random.Random

/**
 * The shared sampling harness for the **WI-4 property pack** (proposal §8) — the kernel-free ∀-laws in
 * `interpreter/commonTest` that quantify over *sampled* inputs rather than pin cases. It provides:
 *
 * - a **deterministic** seed stream ([SEEDS]) — a fixed list, never wall-clock / platform randomness, so JVM and
 *   JS sweep the IDENTICAL samples (`:interpreter:check` runs `commonTest` on both);
 * - a small set of **diverse** [Scenario]s (flat · single-repeatable · nested repeatable) each with a
 *   fill / empty / **malformed** value strategy, so a property stresses the group shapes and the formal-error →
 *   UNKNOWN paths, not one toy model;
 * - document construction ([Scenario.document]) and **placement recording** ([Scenario.placements] + [documentOf])
 *   so the row-permutation property can relabel rows and rebuild.
 *
 * A property author pairs an authored condition with the [Scenario] group it iterates (property 1/2/6) or uses
 * the [Scenario]'s own rules through `DmInterpreter` (property 3/7).
 */
internal object PropertySupport {

    /** The deterministic seed stream — the same 60 samples per model on JVM and JS. */
    val SEEDS: List<Long> = (0L until 60L).toList()

    /** A sampled model + how to instantiate it (rows per repeatable group, value per field). */
    class Scenario(
        val name: String,
        val model: ModelDef,
        private val rows: (String, List<Int>, Random) -> Int,
        private val values: ValueStrategy,
    ) {
        private val gen = SeedGenerator(model)

        /** The document generated for [seed] — deterministic in [seed] (same on JVM + JS). */
        fun document(seed: Long): Document = documentOf(model, placements(seed))

        /** The recorded placement list generated for [seed] — the row-permutation property relabels these. */
        fun placements(seed: Long): List<Placement> {
            val out = ArrayList<Placement>()
            gen.populate(recorder(out), seed, rows, values)
            return out
        }
    }

    /** Build a [Document] of [model] by replaying [placements] (the row-permutation property rebuilds from a
     *  relabeled list this way). */
    fun documentOf(model: ModelDef, placements: List<Placement>): Document =
        model.newDocument().also { placements.replayInto(it.asSink()) }

    private fun recorder(out: MutableList<Placement>): PlacementSink = object : PlacementSink {
        override fun group(path: String, vararg reps: Int) { out += Placement(PlacementKind.GROUP, path, reps.toList()) }
        override fun field(path: String, value: String, vararg reps: Int) { out += Placement(PlacementKind.FIELD, path, reps.toList(), value) }
        override fun emptyField(path: String, vararg reps: Int) { out += Placement(PlacementKind.EMPTY, path, reps.toList()) }
    }

    // ---- value strategy (fill / empty / malformed / colliding index keys) ------------------------------------

    private val MALFORMED_NUMBERS = listOf("abc", "-0", "+5", " 5 ")

    /** A fuzz-like strategy: ~30% empty, index fields drawn from a 3-key pool (collisions), ~12% of NUMBERs
     *  malformed (formal error → UNKNOWN — the suppression paths properties 1/2 need), the rest [ValidValues]. */
    private fun fuzzValues(indexFields: Set<String>): ValueStrategy = ValueStrategy { f, reps, rnd ->
        when {
            rnd.nextDouble() < 0.30 -> null
            f.path in indexFields -> "K" + rnd.nextInt(3)
            f.kind == Kind.NUMBER && rnd.nextDouble() < 0.12 -> MALFORMED_NUMBERS[rnd.nextInt(MALFORMED_NUMBERS.size)]
            else -> ValidValues.value(f, reps, rnd)
        }
    }

    private val zeroToFourRows: (String, List<Int>, Random) -> Int = { _, _, rnd -> rnd.nextInt(0, 5) }

    // ---- the diverse models ----------------------------------------------------------------------------------

    /** A **flat** single-row model — signed & unsigned NUMBER, scaled NUMBER, STRING, BOOLEAN. */
    val flat: Scenario = run {
        val fields = listOf(
            FieldDef("/T/Qty", Kind.NUMBER, scale = 0),
            FieldDef("/T/UQty", Kind.NUMBER, scale = 0, signed = false),
            FieldDef("/T/Price", Kind.NUMBER, scale = 2),
            FieldDef("/T/Name", Kind.STRING),
            FieldDef("/T/Flag", Kind.BOOLEAN),
        )
        val rules = listOf(
            RuleDef("/T/R_Q", "/T", "FieldFilled(Qty) And [Qty] > 50", "Q", "../Qty"),
            RuleDef("/T/R_P", "/T", "[Price] < 10", "P", "../Price"),
            RuleDef("/T/R_N", "/T", "NoFieldFilled(Name, Qty)", "N", "../Name"),
        )
        Scenario("flat", ModelDef(fields, rules), zeroToFourRows, fuzzValues(emptySet()))
    }

    /** A **single-repeatable** model — `/Cart/Lines` (repeatable, index `ItemId`) with per-row + aggregate rules. */
    val singleRep: Scenario = run {
        val idx = "/Cart/Lines/ItemId"
        val fields = listOf(
            FieldDef("/Cart/Budget", Kind.NUMBER, scale = 2),
            FieldDef(idx, Kind.STRING),
            FieldDef("/Cart/Lines/Qty", Kind.NUMBER, scale = 0),
            FieldDef("/Cart/Lines/Amount", Kind.NUMBER, scale = 2),
            FieldDef("/Cart/Lines/Note", Kind.STRING),
        )
        val rules = listOf(
            RuleDef("/Cart/Lines/R_Q", "/Cart/Lines", "FieldFilled(Qty) And [Qty] > 50", "Q", "../Qty"),
            RuleDef("/Cart/R_S", "/Cart", "Sum(Lines*/Amount) > 100", "S", "../Budget"),
        )
        val model = ModelDef(fields, rules, repeatableGroups = setOf("/Cart/Lines"),
            declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(idx))
        Scenario("singleRep", model, zeroToFourRows, fuzzValues(setOf(idx)))
    }

    /** A **nested repeatable** model — `/M/G1` ⊃ `/M/G2` (both repeatable, indexed) with per-row / cross-level /
     *  aggregate rules (absolute paths, so cross-level refs are unambiguous). */
    val nested: Scenario = run {
        val k1 = "/M/G1/K1"; val k2 = "/M/G1/G2/K2"
        val n1 = "/M/G1/N1"; val n2 = "/M/G1/G2/N2"
        val fields = listOf(
            FieldDef(k1, Kind.STRING), FieldDef(n1, Kind.NUMBER, scale = 1),
            FieldDef(k2, Kind.STRING), FieldDef(n2, Kind.NUMBER, scale = 2),
        )
        val rules = listOf(
            RuleDef("/M/G1/G2/R_PR", "/M/G1/G2", "FieldFilled($n2) And [$n2] > 50", "PR", "../N2"),
            RuleDef("/M/G1/G2/R_XL", "/M/G1/G2", "FieldFilled($n2) And [$n2] > [$n1]", "XL", "../N2"),
            RuleDef("/M/G1/R_AG", "/M/G1", "Sum(G2*/N2) > 100", "AG", "../N1"),
        )
        val model = ModelDef(fields, rules, repeatableGroups = setOf("/M/G1", "/M/G1/G2"),
            declaredReps = mapOf("/M/G1" to 1000, "/M/G1/G2" to 1000), indexFields = setOf(k1, k2))
        Scenario("nested", model, zeroToFourRows, fuzzValues(setOf(k1, k2)))
    }

    /** All rule-bearing scenarios — the diversity axis for the whole-model properties (3, 7). */
    val scenarios: List<Scenario> = listOf(flat, singleRep, nested)

    // ---- authored conditions (for the per-condition properties 1 & 2) ----------------------------------------

    /**
     * One authored condition to evaluate over a [scenario]'s documents: its [text] and the [group] it iterates.
     * [kleeneMonotone] marks whether routing a referenced field leaf to UNKNOWN is a genuine **Kleene** operation
     * (property 2) — false for a **counting quantifier** (`NoFieldFilled`/`NotAllFieldsFilled`) where "operand
     * UNKNOWN" is a *tally* change (an operand dropped from a count), not a 3VL leaf going UNKNOWN, so
     * monotonicity does not apply to it; those still exercise property 1 (lazy ≡ eager holds for every construct).
     */
    class Authored(val scenario: Scenario, val group: String, val text: String, val kleeneMonotone: Boolean = true)

    /** A diverse catalog of authored conditions across the models — comparisons, presence, And/Or, aggregates,
     *  quantifiers, cross-level refs — the input population for properties 1 and 2. */
    val authored: List<Authored> = listOf(
        Authored(flat, "/T", "FieldFilled(Qty) And [Qty] > 50"),
        Authored(flat, "/T", "[Price] < 10 Or [Qty] > 100"),
        Authored(flat, "/T", "FieldFilled(Name) And [Price] >= 0"),
        Authored(flat, "/T", "[UQty] >= 0"),
        Authored(flat, "/T", "[Qty] != [Price]"),
        Authored(flat, "/T", "FieldFilled(Name) Or [Qty] < 5"),
        Authored(flat, "/T", "FieldFilled(Flag) And [Qty] > 0"),
        Authored(flat, "/T", "NoFieldFilled(Name, Qty)", kleeneMonotone = false),
        Authored(flat, "/T", "NotAllFieldsFilled(Name, Qty, Price)", kleeneMonotone = false),
        Authored(singleRep, "/Cart/Lines", "FieldFilled(Qty) And [Qty] > [Amount]"),
        Authored(singleRep, "/Cart/Lines", "[Amount] < 10 Or FieldFilled(Note)"),
        Authored(singleRep, "/Cart/Lines", "FieldFilled(ItemId) And [Qty] > 50"),
        Authored(singleRep, "/Cart", "Sum(Lines*/Amount) > 100 And FieldFilled(Budget)"),
        Authored(singleRep, "/Cart", "MinValue(Lines*/Qty) < 5"),
        Authored(singleRep, "/Cart", "MaxValue(Lines*/Amount) > 50"),
        Authored(singleRep, "/Cart", "NumberOfFilledFields(Lines*/Qty) > 1"),
        Authored(nested, "/M/G1/G2", "FieldFilled(/M/G1/G2/N2) And [/M/G1/G2/N2] > 50"),
        Authored(nested, "/M/G1/G2", "[/M/G1/G2/N2] > [/M/G1/N1]"),
        Authored(nested, "/M/G1", "Sum(G2*/N2) > 100"),
    )

    /** The distinct NON-outer field-reference paths in [cond] — the operands property 2 routes to UNKNOWN one at
     *  a time (a starred aggregate's field is an [Expr.Star], not collected — routing a leaf field it doesn't read
     *  is a no-op, so only the genuine [Expr.Field] leaves are routed). */
    fun fieldPathsOf(cond: Cond): Set<String> {
        val out = LinkedHashSet<String>()
        fun walkExpr(e: Expr) {
            when (e) {
                is Expr.Field -> if (!e.outer) out += e.path
                is Expr.Func -> e.args.forEach(::walkExpr)
                is Expr.Arith -> { walkExpr(e.left); walkExpr(e.right) }
                else -> {}
            }
        }
        fun walk(c: Cond) {
            when (c) {
                is Cond.Cmp -> { walkExpr(c.left); walkExpr(c.right) }
                is Cond.Tolerance -> { walkExpr(c.left); walkExpr(c.right) }
                is Cond.ValueListQuant -> { c.fields.forEach(::walkExpr); c.values.forEach(::walkExpr) }
                is Cond.And -> { walk(c.left); walk(c.right) }
                is Cond.Or -> { walk(c.left); walk(c.right) }
                is Cond.Not -> walk(c.c)
                is Cond.Pred -> c.args.forEach(::walkExpr)
                is Cond.Pattern -> { walkExpr(c.value); walkExpr(c.pattern) }
                is Cond.Custom -> {}
            }
        }
        walk(cond)
        return out
    }
}
