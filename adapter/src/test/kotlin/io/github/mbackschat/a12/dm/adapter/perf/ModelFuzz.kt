package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder
import java.util.function.Consumer
import kotlin.random.Random

/**
 * **Harness E — model fuzzing.** Where the document fuzz (`InterpreterKernelFuzzTest`/`FullMessageFuzzDiffTest`)
 * varies *documents* over one fixed model, this varies the **model**: random-depth (2..4) chains of nested
 * repeatable groups — the **deep-nesting structural space the fixed 2-level fuzz model can't reach** (CLAUDE.md
 * flags 3–4 nested levels + cross-level refs as where a naive impl breaks). Each level `Gi` carries an index
 * `Key{i}` + a `NUMBER N{i}` (random scale), with a random rule (per-row / cross-level / aggregate). Built
 * **valid-by-construction** via modelkit's `DocumentModelDraft.toJson()` (kernel) + a matching [ModelDef]
 * (interpreter); the rule is injected with [RuleDef]/`RuntimeLaws.withRule`. Each model becomes a
 * [PerfScenario] the full-message differential ([FullMessageFuzzDiffTest]'s machinery) sweeps over fuzzed docs.
 *
 * modelkit does not expose `positivesOnly`, so generated numbers are signed (the unsigned-polarity axis stays
 * covered by the fixed `DirectionalPolarityDiffTest`); the structural axis fuzzed here is depth + repeatability
 * + scale + rule shape.
 */
object ModelFuzz {

    /** Build a random nested model for [modelSeed] as a differential [PerfScenario]. */
    fun model(modelSeed: Int): PerfScenario {
        val rnd = Random(modelSeed.toLong())
        val depth = rnd.nextInt(2, 5)                       // 2..4 nested repeatable levels
        // Declared repeatability is kept HIGH (like the real fuzz/perf fixtures) so the generator's small row
        // counts never exceed it: the kernel's `zuGrosseZeile`/`zuGrosseKontextnummer` *structural* bound check
        // (rows beyond `repeatability`) is a document-consistency concern the runtime interpreter does not model
        // (KERNEL-FINDINGS) — out of scope for this rule-exactness differential, so we don't provoke it.
        val declaredMax = 1000
        val scales = IntArray(depth + 1) { rnd.nextInt(0, 3) }

        fun groupPath(level: Int) = "/M/" + (1..level).joinToString("/") { "G$it" }
        fun nPath(level: Int) = "${groupPath(level)}/N$level"
        fun keyPath(level: Int) = "${groupPath(level)}/Key$level"

        // Recursive modelkit group builder: each level is a repeatable group (Key index + a NUMBER), nesting the next.
        fun build(level: Int): Consumer<GroupBuilder> = Consumer { g ->
            g.repeatable(declaredMax).indexField("Key$level")
            g.field("Key$level", FieldKind.STRING)
            g.numberField("N$level", Consumer<NumberFieldBuilder> { it.fractionDigits(scales[level]) })
            if (level < depth) g.group("G${level + 1}", build(level + 1))
        }
        val structureJson = DocumentModelDraft.named("m").locale("en_US")
            .rootGroup("M", Consumer { it.group("G1", build(1)) })
            .build().toJson()

        // A random rule, placed so its error field is referenced (the kernel's MVK_ERROR_FIELD_NOT_REFERENCED).
        val (group, code, errorRel, cond) = randomRule(rnd, depth, ::nPath)
        val kernelModel = RuntimeLaws.withRule(structureJson, group, "R_$code", errorRel, code, cond)

        val fields = (1..depth).flatMap {
            listOf(FieldDef(keyPath(it), Kind.STRING), FieldDef(nPath(it), Kind.NUMBER, scale = scales[it]))
        }
        val repeatable = (1..depth).map { groupPath(it) }.toSet()
        val interpModel = ModelDef(
            fields, listOf(RuleDef("$group/R_$code", group, cond, code, errorRel)),
            repeatableGroups = repeatable,
            declaredReps = (1..depth).associate { groupPath(it) to declaredMax },
            indexFields = (1..depth).map { keyPath(it) }.toSet(),
        )
        val gen = SeedGenerator(interpModel)
        val indexFields = (1..depth).map { keyPath(it) }.toSet()
        val rows: (String, List<Int>, Random) -> Int = { g, _, r -> if (g in repeatable) r.nextInt(0, 4) else 0 }
        val values = ValueStrategy { f, _, r ->
            when {
                r.nextDouble() < 0.3 -> null
                f.path in indexFields -> "K" + r.nextInt(3)         // small pool → collisions exercise uniqueIndex
                else -> r.nextInt(0, 100).toString()
            }
        }
        return PerfScenario("modelfuzz#$modelSeed d=$depth", kernelModel, interpModel) { sink, _, variant ->
            gen.populate(sink, seed = variant.toLong(), rowsOf = rows, value = values)
        }
    }

    /**
     * Build a random **nvv (raw-type) model** for [modelSeed] — the IG71/IF125 net. Two `noValueValidation`
     * string fields (one root-level, one inside a repeatable group) with the LEGAL rule pool over them: the
     * eliminated meta-length shape (`Length(nvv) > c`, both mirror directions, random bounds incl. always-true
     * ones — the shape that must NEVER fire), presence checks, quantifiers, and content counts (which must
     * fire normally). nvv values are sometimes multi-line (legal — the format check is skipped) and of random
     * length, so a mis-evaluated Length would split immediately. No prior net could generate an nvv field —
     * which is why the IG71 divergence survived every sweep.
     */
    fun nvvModel(modelSeed: Int): PerfScenario {
        val rnd = Random(modelSeed.toLong())
        val structureJson = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", Consumer { g ->
                g.stringField("Note") { n -> n.lineBreaks().noValueValidation() }
                g.field("Other", FieldKind.STRING)
                g.field("Ref", FieldKind.STRING)
                g.group("Items", Consumer { i ->
                    i.repeatable(1000)
                    i.stringField("Blob") { b -> b.lineBreaks().noValueValidation() }
                    i.field("Name", FieldKind.STRING)
                })
            })
            .build().toJson()

        val spec = nvvRule(rnd)
        val kernelModel = RuntimeLaws.withRule(structureJson, spec.group, "R_${spec.code}", spec.errorRel, spec.code, spec.cond)

        val nvvPaths = setOf("/Claim/Note", "/Claim/Items/Blob")
        val interpModel = ModelDef(
            listOf(
                FieldDef("/Claim/Note", Kind.STRING, noValueValidation = true, lineBreaksPermitted = true),
                FieldDef("/Claim/Other", Kind.STRING),
                FieldDef("/Claim/Ref", Kind.STRING),
                FieldDef("/Claim/Items/Blob", Kind.STRING, noValueValidation = true, lineBreaksPermitted = true),
                FieldDef("/Claim/Items/Name", Kind.STRING),
            ),
            listOf(RuleDef("${spec.group}/R_${spec.code}", spec.group, spec.cond, spec.code, spec.errorRel)),
            repeatableGroups = setOf("/Claim/Items"),
            declaredReps = mapOf("/Claim/Items" to 1000),
        )
        val gen = SeedGenerator(interpModel)
        val rows: (String, List<Int>, Random) -> Int = { g, _, r -> if (g == "/Claim/Items") r.nextInt(0, 4) else 0 }
        val values = ValueStrategy { f, _, r ->
            when {
                r.nextDouble() < 0.3 -> null
                f.path in nvvPaths -> buildString {
                    repeat(r.nextInt(1, 13)) { append(('a' + r.nextInt(26))) }
                    if (r.nextDouble() < 0.3) insert(r.nextInt(length), '\n')   // multi-line raw payloads are legal
                }
                else -> "t" + r.nextInt(0, 50)
            }
        }
        return PerfScenario("nvvfuzz#$modelSeed ${spec.code}", kernelModel, interpModel) { sink, _, variant ->
            gen.populate(sink, seed = variant.toLong(), rowsOf = rows, value = values)
        }
    }

    /** One legal rule over the nvv fields: the eliminated meta-length shape (never fires) or an ordinary
     *  presence/quantifier/count rule (fires normally). Every shape references its error field. */
    private fun nvvRule(rnd: Random): RuleSpec {
        val bound = rnd.nextInt(-1, 15)
        return when (rnd.nextInt(6)) {
            0 -> RuleSpec("/Claim", "LG", "../Note", "Length(Note) > $bound")
            1 -> RuleSpec("/Claim", "LM", "../Note", "$bound < Length(Note)")
            2 -> RuleSpec("/Claim", "PN", "../Note", "FieldNotFilled(Note)")
            3 -> RuleSpec("/Claim", "QA", "../Other", "AllFieldsFilled(Note, Other)")
            4 -> RuleSpec("/Claim/Items", "RB", "../Blob", "GroupFilled(/Claim/Items) And FieldNotFilled(Blob)")
            else -> RuleSpec("/Claim", "CT", "../Ref", "FieldFilled(Ref) And NumberOfFilledFields(Items*/Blob) > ${rnd.nextInt(0, 3)}")
        }
    }

    private data class RuleSpec(val group: String, val code: String, val errorRel: String, val cond: String)

    /** Pick a rule shape that stresses the nesting: per-row at the deepest level, a cross-level ref to the
     *  top-level number, or an aggregate of a child over its parent. All reference their error field. */
    private fun randomRule(rnd: Random, depth: Int, nPath: (Int) -> String): RuleSpec {
        fun groupPath(level: Int) = "/M/" + (1..level).joinToString("/") { "G$it" }
        return when (rnd.nextInt(if (depth >= 2) 3 else 1)) {
            0 -> RuleSpec(groupPath(depth), "PR", "../N$depth", "FieldFilled(N$depth) And [N$depth] > 50")
            1 -> RuleSpec(groupPath(depth), "XL", "../N$depth", "FieldFilled(N$depth) And [N$depth] > [${nPath(1)}]")
            else -> RuleSpec(groupPath(depth - 1), "AG", "../G$depth/N$depth", "Sum(G$depth*/N$depth) > 100")
        }
    }
}
