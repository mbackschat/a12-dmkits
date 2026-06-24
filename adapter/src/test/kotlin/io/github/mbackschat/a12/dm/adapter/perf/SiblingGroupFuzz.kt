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
import java.util.function.Consumer
import kotlin.random.Random

/**
 * **Harness G — sibling-group fuzzing.** Every other net fuzzes either nested *chains* ([ModelFuzz] — `G1 ⊃ G2
 * ⊃ G3`) or a fixed two-level model ([PerfCatalog.fuzz]); none ever produces **two sibling repeatable groups**
 * (`A` ⊥ `B` under a common non-repeatable root, neither an ancestor of the other). That shape is where the
 * silent parallel-iteration bug lived — so the net was structurally blind to the whole class. This generator
 * builds it: a `/Plan` root with sibling repeatable groups `A`(Dept, Va) and `B`(Dept, Vb) sharing an index
 * field name, then a rule.
 *
 * This half generates only **supported** rules — ones whose iteration scope is a SINGLE group (a per-row rule
 * on `A` or `B`) or whose cross-sibling reference is **aggregated** (a star collapses the sibling to a scalar,
 * so no joint iteration is needed) — and asserts the interpreter agrees with the kernel at the full-message
 * level. It is the multi-sibling coverage the validation nets lacked: independent iteration of two sibling
 * repetition lists, and cross-sibling aggregates. The **parallel-iteration** rules (a per-row reference into
 * BOTH siblings — the index-keyed outer join) are the acceptance gate of that feature, [parallelCase].
 */
object SiblingGroupFuzz {

    private const val ROOT = "/Plan"
    private const val A = "/Plan/A"
    private const val B = "/Plan/B"
    private val FIELDS = listOf(
        FieldDef("$A/Dept", Kind.STRING), FieldDef("$A/Va", Kind.NUMBER, scale = 0),
        FieldDef("$B/Dept", Kind.STRING), FieldDef("$B/Vb", Kind.NUMBER, scale = 0),
    )

    /** A generated rule over the sibling model: where it sits, its code, its referenced error field, its text. */
    private data class Spec(val group: String, val code: String, val errorRel: String, val cond: String)

    /** A parallel-iteration case (NOT supported yet): the scenario + the rule's code and interpreter path. */
    data class ParallelCase(val scenario: PerfScenario, val code: String, val rulePath: String)

    /** The shared structure JSON (kernel) for the two-sibling `/Plan` model — built once, rules injected per case. */
    private val structureJson: String = DocumentModelDraft.named("plan").locale("en_US")
        .rootGroup("Plan", Consumer { root ->
            root.group("A", Consumer { g ->
                g.repeatable(50).indexField("Dept")
                g.field("Dept", FieldKind.STRING)
                g.numberField("Va", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
            })
            root.group("B", Consumer { g ->
                g.repeatable(50).indexField("Dept")
                g.field("Dept", FieldKind.STRING)
                g.numberField("Vb", Consumer<NumberFieldBuilder> { it.fractionDigits(0) })
            })
        })
        .build().toJson()

    /** A **supported** sibling scenario for [modelSeed]: one rule (single-group per-row, or cross-sibling aggregate). */
    fun supported(modelSeed: Int): PerfScenario {
        val t = 1 + modelSeed % 90    // vary the threshold per model
        val spec = when (modelSeed % 4) {
            0 -> Spec(A, "RA", "../Va", "FieldFilled(Va) And [Va] > $t")                 // per-row on A
            1 -> Spec(B, "RB", "../Vb", "FieldFilled(Vb) And [Vb] > $t")                 // per-row on B
            2 -> Spec(ROOT, "SA", "../A/Va", "Sum(A*/Va) > ${t * 2}")                    // aggregate over A
            else -> Spec(ROOT, "SS", "../A/Va", "Sum(A*/Va) > $t Or Sum(B*/Vb) > $t")    // two independent sibling aggregates
        }
        return scenario("siblingfuzz#$modelSeed ${spec.code}", spec)
    }

    /**
     * A **parallel-iteration** case for [modelSeed] (a per-row reference into BOTH siblings, joined by the shared
     * `Dept` index). The interpreter evaluates it as the index-keyed outer join and must agree with the kernel
     * at the full-message level — across matched, A-only, B-only values AND the occasional invalid index
     * (duplicate/empty → the 3VL corner, KERNEL-FINDINGS §9), all of which the value strategy generates.
     */
    fun parallelCase(modelSeed: Int): ParallelCase {
        val spec = when (modelSeed % 2) {
            0 -> Spec(ROOT, "PC", "../A/Va", "FieldsNotCollectivelyFilled(A/Va, B/Vb)")
            else -> Spec(ROOT, "PX", "../A/Va", "FieldFilled(A/Va) And FieldFilled(B/Vb) And [A/Va] > [B/Vb]")
        }
        return ParallelCase(scenario("siblingparallel#$modelSeed ${spec.code}", spec), spec.code, "${spec.group}/${spec.code}")
    }

    private fun scenario(name: String, spec: Spec): PerfScenario {
        val kernelModel = RuntimeLaws.withRule(structureJson, spec.group, spec.code, spec.errorRel, spec.code, spec.cond)
        val interpModel = ModelDef(
            FIELDS, listOf(RuleDef("${spec.group}/${spec.code}", spec.group, spec.cond, spec.code, spec.errorRel)),
            repeatableGroups = setOf(A, B),
            declaredReps = mapOf(A to 50, B to 50),
            indexFields = setOf("$A/Dept", "$B/Dept"),
        )
        val gen = SeedGenerator(interpModel)
        // Rows: 0..5 per sibling. Index Dept is mostly UNIQUE per group — `D<rowIndex>` — so A and B share keys
        // `D1..D_min` (matched join rows) and diverge beyond (unmatched → the `-5` synthetic side), heavily
        // exercising matched / A-only / B-only values. ~10% of cells are deliberately INVALID — empty (→
        // `mandatoryField`) or a fixed `DUP` collision (→ `uniqueIndex`) — exercising the invalid-index 3VL
        // (an unmatched ref into a compromised group reads UNKNOWN, KERNEL-FINDINGS §9), which the interpreter
        // now models exactly. Numbers: random 0..100 or ~30% empty (→ empty-as-0 across the join).
        val rows: (String, List<Int>, Random) -> Int = { g, _, r -> if (g == A || g == B) r.nextInt(0, 6) else 0 }
        val indexFields = setOf("$A/Dept", "$B/Dept")
        val values = ValueStrategy { f, reps, r ->
            when {
                f.path in indexFields -> when {
                    r.nextDouble() < 0.1 -> null                 // empty index → mandatoryField + invalid-index 3VL
                    r.nextDouble() < 0.1 -> "DUP"                // forced collision → uniqueIndex + invalid-index 3VL
                    else -> "D" + reps[reps.size - 2]            // unique within group, overlapping across groups
                }
                r.nextDouble() < 0.3 -> null
                else -> r.nextInt(0, 101).toString()
            }
        }
        return PerfScenario(name, kernelModel, interpModel) { sink, _, variant ->
            gen.populate(sink, seed = variant.toLong(), rowsOf = rows, value = values)
        }
    }
}
