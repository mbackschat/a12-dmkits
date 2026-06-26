package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef
import java.util.function.Consumer

/**
 * **THE COMBINED REAL-WORLD WORST-CASE benchmark** — the faithful "complex tax-like" model (the user's real
 * validation shape: thousands of fields, thousands of rules, many crossing groups, summing, references to
 * top-level fields, scaled on the data axis), modeled in an own **insurance-claims** domain. It is the one
 * benchmark that stresses **all axes at once** — huge rows × many fields × deep nesting × many rules × many
 * up/sibling references — so an *interaction-effect* O(N²) (rules × rows × cross-ref walk) cannot hide behind a
 * single-axis benchmark.
 *
 * **Authored with the dmkits themselves, not free-text condition strings** (the project thesis: the typed
 * builder beats fragile strings, so the benchmark dogfoods it): the structure is built with **modelkit**
 * ([DocumentModelDraft]); every rule condition is built with the **rulekit typed DSL** ([Dsl] + [BoundModel]
 * handles) and **parametric combinators** — the same shape as the `MirroringRuleStudyTest` flagship — then
 * rendered to the canonical string ([DslRenderer]) that feeds BOTH engines (the kernel via
 * [RuntimeLaws.withRule], the interpreter via [RuleDef]). One typed source of truth → one string → no drift,
 * no hand-typed operator/path that a typo could break.
 *
 * The realistic rule mix:
 * - **per-row comparisons** and **walk-ups to top-level fields** (1 and 3 parents up) — the data-axis stressors;
 * - **cross-sibling** references (a section compares to its left neighbour);
 * - the **anonymized tax consistency / mirroring rule** ([consistencyRule]) — `GroupFilled` context (the legacy
 *   "Kontext" is a Group, KERNEL-FINDINGS §1) + "target has data" + an OR of guarded null-safe field-agreement
 *   blocks (`agreesWith`) — emitted **many times** (one per consistency section) with a branch-count variation;
 * - a **numeric mirroring** variant at the Claim level (per-row → scales with the data axis), via the numeric
 *   [io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef.agreesWith];
 * - a **`Having`-filtered Sum**, a **deep nested-star Sum**, and a **cross-group Sum-vs-Sum** (Claim vs the
 *   Audit sibling).
 *
 * The scale knob N = the Claim count (× [VISITS_PER_CLAIM] × [CHARGES_PER_VISIT] Charge rows). [Shape] sizes the
 * fields/rules; [FULL] is the ≈1000-field/≈1000-rule real-world target, [GATE] a reduced shape for the CI gate.
 */
object ComplexRealWorld {

    /** Sizes the model: the field/rule breadth + depth. The data-axis scale (rows) is the separate N knob. */
    data class Shape(
        /** sibling NUMBER-only sections (`/Pol/Num{c}`), each with [numberFieldsPerSection] fields + 1 rule each. */
        val numericSections: Int,
        val numberFieldsPerSection: Int,
        /** sibling consistency sections (`/Pol/Con{c}`), each hosting ONE mirroring (tax-shape) rule. */
        val consistencySections: Int,
        /** NUMBER fields per repeatable deep level (Claim/Visit/Charge/Audit), each with 1 rule. */
        val deepFields: Int,
        /** top-level singleton NUMBER fields (`/Pol/Cap{i}`) — walk-up + cross-group targets. */
        val topFields: Int,
        /** the mirroring rule's branch count (4 = entity × address 2×2; 2 = entity only). */
        val branches: Int,
    )

    /** ≈1000 fields / ≈1000 rules — the user's real-world target shape. */
    val FULL = Shape(numericSections = 45, numberFieldsPerSection = 20, consistencySections = 20,
        deepFields = 12, topFields = 40, branches = 4)

    /** A reduced shape for the CI correctness gate (fast kernel codegen) — same structure, ~100 rules. */
    val GATE = Shape(numericSections = 6, numberFieldsPerSection = 6, consistencySections = 3,
        deepFields = 6, topFields = 10, branches = 2)

    private const val DECLARED_MAX = 200_000
    private const val POL = "/Pol"
    private const val CLAIM = "/Pol/Claim"
    private const val VISIT = "/Pol/Claim/Visit"
    private const val CHARGE = "/Pol/Claim/Visit/Charge"
    private const val AUDIT = "/Pol/Audit"

    /** The deep chain's fixed fan-out per parent row — so total Charge rows = N × this product. */
    const val VISITS_PER_CLAIM = 3
    const val CHARGES_PER_VISIT = 2

    /** The STRING field block of a consistency section — the policyholder block mirrored onto the schedule. */
    private val CON_STRINGS = listOf(
        "SchedName", "SchedZip", "SchedCity",                 // target (the restated schedule block)
        "OrgName", "PersonName", "CorrZip", "CorrCity", "ResZip", "ResCity",   // the source alternatives
        "IsOrg",                                              // the entity-type selector axis
    )

    private val num0 = Consumer<NumberFieldBuilder> { it.fractionDigits(0) }

    /** Build the benchmark scenario for [shape], in the given firing [firing] regime. */
    fun scenario(shape: Shape, firing: Boolean): PerfScenario {
        val modelJson = buildModel(shape)
        val m = BoundModel.ofJson(modelJson)
        val rules = buildRules(m, shape)
        val kernelModel = rules.fold(modelJson) { json, r ->
            RuntimeLaws.withRule(json, r.group, r.name, r.errorRel, r.name, r.rendered)
        }
        val interpModel = ModelDef(
            buildFields(shape),
            rules.map { RuleDef("${it.group}/${it.name}", it.group, it.rendered, it.name, it.errorRel) },
            repeatableGroups = setOf(CLAIM, VISIT, CHARGE, AUDIT),
            declaredReps = mapOf(CLAIM to DECLARED_MAX, VISIT to DECLARED_MAX, CHARGE to DECLARED_MAX, AUDIT to DECLARED_MAX),
            indexFields = setOf("$CLAIM/ClaimKey", "$VISIT/VisitKey", "$CHARGE/ChargeKey", "$AUDIT/AuditKey"),
        )
        val gen = SeedGenerator(interpModel)
        return PerfScenario(
            "complexRealWorld(${interpModel.fields.size}f/${interpModel.rules.size}r${if (firing) "" else ",low-firing"})",
            kernelModel, interpModel,
        ) { sink, n, _ -> gen.populate(sink, rowsOf = { g, _, _ -> rowsFor(g, n) }, value = values(firing)) }
    }

    /** Row count per repeatable group: the deep chain + Audit scale with N (Claim count); breadth is singleton. */
    private fun rowsFor(group: String, n: Int): Int = when (group) {
        CLAIM, AUDIT -> n
        VISIT -> VISITS_PER_CLAIM
        CHARGE -> CHARGES_PER_VISIT
        else -> 0
    }

    // ---- the model (modelkit DocumentModelDraft) -------------------------------------------------------------
    private fun buildModel(s: Shape): String =
        DocumentModelDraft.named("pol").locale("en_US").rootGroup("Pol", Consumer { root ->
            for (i in 0 until s.topFields) root.numberField("Cap$i", num0)
            for (c in 0 until s.numericSections) root.group("Num$c", Consumer { g ->
                for (f in 0 until s.numberFieldsPerSection) g.numberField("P$f", num0)
            })
            for (c in 0 until s.consistencySections) root.group("Con$c", Consumer { g ->
                for (name in CON_STRINGS) g.field(name, FieldKind.STRING)
                g.numberField("Guard", num0)
                g.group("Ctx", Consumer { ctx -> ctx.field("Submitted", FieldKind.STRING) })
            })
            root.group("Claim", Consumer { claim ->
                claim.repeatable(DECLARED_MAX).indexField("ClaimKey")
                claim.field("ClaimKey", FieldKind.STRING)
                for (f in 0 until s.deepFields) claim.numberField("CL$f", num0)
                claim.group("Meta", Consumer { meta -> meta.field("Flag", FieldKind.STRING) })
                claim.group("Visit", Consumer { visit ->
                    visit.repeatable(DECLARED_MAX).indexField("VisitKey")
                    visit.field("VisitKey", FieldKind.STRING)
                    for (f in 0 until s.deepFields) visit.numberField("VS$f", num0)
                    visit.group("Charge", Consumer { charge ->
                        charge.repeatable(DECLARED_MAX).indexField("ChargeKey")
                        charge.field("ChargeKey", FieldKind.STRING)
                        for (f in 0 until s.deepFields) charge.numberField("CH$f", num0)
                    })
                })
            })
            root.group("Audit", Consumer { audit ->
                audit.repeatable(DECLARED_MAX).indexField("AuditKey")
                audit.field("AuditKey", FieldKind.STRING)
                for (f in 0 until s.deepFields) audit.numberField("AU$f", num0)
            })
        }).build().toJson()

    /** The model's fields as interpreter [FieldDef]s — mirrors [buildModel] exactly. */
    private fun buildFields(s: Shape): List<FieldDef> = buildList {
        for (i in 0 until s.topFields) add(num("$POL/Cap$i"))
        for (c in 0 until s.numericSections) for (f in 0 until s.numberFieldsPerSection) add(num("$POL/Num$c/P$f"))
        for (c in 0 until s.consistencySections) {
            val b = "$POL/Con$c"
            for (name in CON_STRINGS) add(str("$b/$name"))
            add(num("$b/Guard"))
            add(str("$b/Ctx/Submitted"))
        }
        add(str("$CLAIM/ClaimKey")); for (f in 0 until s.deepFields) add(num("$CLAIM/CL$f")); add(str("$CLAIM/Meta/Flag"))
        add(str("$VISIT/VisitKey")); for (f in 0 until s.deepFields) add(num("$VISIT/VS$f"))
        add(str("$CHARGE/ChargeKey")); for (f in 0 until s.deepFields) add(num("$CHARGE/CH$f"))
        add(str("$AUDIT/AuditKey")); for (f in 0 until s.deepFields) add(num("$AUDIT/AU$f"))
    }

    private fun num(path: String) = FieldDef(path, Kind.NUMBER, scale = 0)
    private fun str(path: String) = FieldDef(path, Kind.STRING)

    // ---- the rules (rulekit typed DSL + parametric combinators) ---------------------------------------------
    /** One authored rule: its target group + unique name/code + relative error field + the DSL-rendered condition. */
    private class DslRule(val group: String, val name: String, val errorRel: String, cond: Condition) {
        val rendered: String = DslRenderer().render(cond)
    }

    private fun buildRules(m: BoundModel, s: Shape): List<DslRule> = buildList {
        var seq = 0
        fun name() = "C%05d".format(seq++)

        // (1) top-level singleton per-row rules
        for (i in 0 until s.topFields) {
            val cap = m.num("$POL/Cap$i")
            add(DslRule(POL, name(), "../Cap$i", cap.filled().and(cap.gt(50))))
        }
        // (2) numeric sections (the bulk) — per-row / walk-up-to-top / cross-sibling
        for (c in 0 until s.numericSections) for (f in 0 until s.numberFieldsPerSection) {
            val p = m.num("$POL/Num$c/P$f")
            val cond = when (f % 3) {
                0 -> p.filled().and(p.gt(50))
                1 -> p.filled().and(p.gt(m.num("$POL/Cap${f % s.topFields}")))                  // walk-up to top-level
                else -> if (c == 0) p.filled().and(p.gt(50))                                     // Num0 has no left sibling
                else p.filled().and(p.gt(m.num("$POL/Num${c - 1}/P0")))                          // cross-sibling reference
            }
            add(DslRule("$POL/Num$c", name(), "../P$f", cond))
        }
        // (3) consistency sections — the anonymized tax mirroring rule, emitted MANY times
        for (c in 0 until s.consistencySections) {
            add(DslRule("$POL/Con$c", name(), "../SchedName", consistencyRule(m, "$POL/Con$c", s.branches)))
        }
        // (4) deep-chain comparison rules — per-row + cross-level walk-up (the data-axis interaction stressors)
        for (f in 0 until s.deepFields) {
            val cl = m.num("$CLAIM/CL$f")
            add(DslRule(CLAIM, name(), "../CL$f",
                if (f % 2 == 0) cl.filled().and(cl.gt(50)) else cl.filled().and(cl.gt(m.num("$POL/Cap${f % s.topFields}")))))
            val vs = m.num("$VISIT/VS$f")
            add(DslRule(VISIT, name(), "../VS$f", when (f % 3) {
                0 -> vs.filled().and(vs.gt(50))
                1 -> vs.filled().and(vs.gt(m.num("$CLAIM/CL0")))                                 // 1 parent up
                else -> vs.filled().and(vs.gt(m.num("$POL/Cap${f % s.topFields}")))              // 3 levels up to top
            }))
            val ch = m.num("$CHARGE/CH$f")
            add(DslRule(CHARGE, name(), "../CH$f", when (f % 3) {
                0 -> ch.filled().and(ch.gt(50))
                1 -> ch.filled().and(ch.gt(m.num("$VISIT/VS0")))                                 // 1 parent up
                else -> ch.filled().and(ch.gt(m.num("$POL/Cap${f % s.topFields}")))              // deep walk-up to top
            }))
            val au = m.num("$AUDIT/AU$f")
            add(DslRule(AUDIT, name(), "../AU$f", au.filled().and(au.gt(50))))
        }
        // (5) deep NUMERIC mirroring (per-claim → scales with rows): numeric agreesWith over CL pairs, GroupFilled context
        val pairs = (2 until minOf(s.deepFields, 8) step 2).map { m.num("$CLAIM/CL$it").agreesWith(m.num("$CLAIM/CL${it + 1}")) }
        add(DslRule(CLAIM, name(), "../CL1",
            m.group("$CLAIM/Meta").filled().and(Dsl.allOf(pairs)).and(m.num("$CLAIM/CL1").gt(50))))
        // (6) Claim-level Having-filtered Sums (cross-group aggregate at depth), each over a distinct Visit field
        for (k in 0 until minOf(s.deepFields, 6)) {
            add(DslRule(CLAIM, name(), "../Visit/VS$k",
                Dsl.sum(m.flatten("$CLAIM/Visit*/VS$k").having(m.num("$VISIT/VS1").gt(5))).gt(100)))
        }
        // (7) model-wide aggregates: deep nested-star Sum + cross-group Sum-vs-Sum (Claim vs the Audit sibling)
        add(DslRule(POL, name(), "../Claim/Visit/Charge/CH0",
            Dsl.sum(m.flatten("$POL/Claim*/Visit*/Charge*/CH0")).gt("10000000000")))
        add(DslRule(POL, name(), "../Claim/CL0",
            Dsl.sum(m.flatten("$POL/Claim*/CL0")).gt(Dsl.sum(m.flatten("$POL/Audit*/AU0")))))
    }

    // ---- the mirroring (tax-shape consistency) combinators — the MirroringRuleStudyTest flagship pattern -----
    /** One guarded source alternative: when [whenCond] holds, the target must mirror [sources]. */
    private data class SourceChoice(val whenCond: Condition, val sources: List<StringRef>)

    /** Null-safe block mirror: each target field agrees with its source (both filled & equal, or both empty). */
    private fun mirrors(sources: List<StringRef>, target: List<StringRef>): Condition =
        Dsl.allOf(target.indices.map { i -> sources[i].agreesWith(target[i]) })

    /** The whole rule SHAPE, parameterized: a [GroupRef] context guard (Kontext=Group → `GroupFilled`) + "target
     *  has data" + an OR of guarded mirrors — the anonymized real-world tax-declaration consistency rule. */
    private fun mirrorRule(context: GroupRef, target: List<StringRef>, choices: List<SourceChoice>): Condition =
        context.filled()
            .and(Dsl.atLeastOneFieldFilled(target.map { it.field() }))
            .and(Dsl.anyOf(choices.map { it.whenCond.and(mirrors(it.sources, target)) }))

    /** Build a consistency section's mirroring rule over its STRING block, with a trailing NUMBER guard so it
     *  fires only in the firing regime while the whole boolean tree is evaluated in both. */
    private fun consistencyRule(m: BoundModel, base: String, branches: Int): Condition {
        val target = listOf(m.str("$base/SchedName"), m.str("$base/SchedZip"), m.str("$base/SchedCity"))
        val isOrg = m.str("$base/IsOrg")
        val correspondence = listOf(m.field("$base/CorrZip"), m.field("$base/CorrCity"))
        fun sources(org: Boolean, corr: Boolean) = listOf(
            if (org) m.str("$base/OrgName") else m.str("$base/PersonName"),
            if (corr) m.str("$base/CorrZip") else m.str("$base/ResZip"),
            if (corr) m.str("$base/CorrCity") else m.str("$base/ResCity"))
        val choices = buildList {
            for (org in listOf(true, false)) {
                if (branches >= 4) {
                    for (corr in listOf(true, false)) {
                        val sel = (if (org) isOrg.filled() else isOrg.notFilled())
                            .and(if (corr) Dsl.atLeastOneFieldFilled(correspondence) else Dsl.noFieldFilled(correspondence))
                        add(SourceChoice(sel, sources(org, corr)))
                    }
                } else {
                    add(SourceChoice(if (org) isOrg.filled() else isOrg.notFilled(), sources(org, true)))
                }
            }
        }
        return mirrorRule(m.group("$base/Ctx"), target, choices).and(m.num("$base/Guard").gt(50))
    }

    // ---- the instance value strategy -------------------------------------------------------------------------
    /** Index keys unique per row; STRING mirroring fields a constant `"X"` (so every `agreesWith` pair is
     *  filled+equal → the mirror tree is TRUE and the trailing NUMBER guard controls firing); `Cap*` = 10 (a low
     *  walk-up bar); `AU*` = 1 (so the cross-group Sum-vs-Sum is cleanly false low-firing / true firing); every
     *  other NUMBER field = 1 (below every threshold → 0 fires) or 5000 (firing → above the bars). */
    private fun values(firing: Boolean): ValueStrategy = ValueStrategy { field, reps, _ ->
        val leaf = field.path.substringAfterLast("/")
        when (field.kind) {
            Kind.STRING -> if (leaf.endsWith("Key")) "K" + reps.joinToString("_") else "X"
            Kind.NUMBER -> when {
                leaf.startsWith("Cap") -> "10"
                leaf.startsWith("AU") -> "1"
                else -> if (firing) "5000" else "1"
            }
            else -> "0"
        }
    }
}
