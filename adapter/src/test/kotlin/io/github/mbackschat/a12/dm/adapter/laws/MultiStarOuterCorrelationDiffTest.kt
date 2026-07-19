package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential lock (IF144, graduated from IG98) — a **MULTI-star** `$`-equality-correlated FILL QUANTIFIER
 * (`MoreThanOneFieldFilled(/Perf/Lines* then Subs* then Cost Having [Cost] == [$Cost])`, a per-Sub rule whose
 * star spans EVERY Line's Subs). This is the shape `Interpreter.starQuantTally` used to route unconditionally to
 * the O(N²) `naiveStarQuantTally` (because `isMultiStar` is true); IF144 now sends the recognized equality join
 * through the O(1) hash join with the FIRST-star bind-depth scope — EXCEPT a multi-star `$`-correlation carrying
 * a `CurrentRepetition` self-exclusion (which drops more than one row: every row sharing the outer's leaf index
 * across the wider scope), which the hash join's single-row self-subtraction cannot express, so it stays naive.
 *
 * This test pins interp == kernel across both paths: the no-self-exclusion cases exercise the NEW multi-star hash
 * join (a Cost shared ACROSS Lines is counted across Lines — the multi-star scope, not the current Line's Subs;
 * empty keys and unevaluated rows drop); the self-exclusion case exercises the naive fallback. IF144's legality
 * was settled here (kernel MVK-accepts + interp matches); the scaling win is `MultiStarCorrelationPerfTest`.
 */
class MultiStarOuterCorrelationDiffTest {

    private val perf = RuntimeLaws.model("models/perf.dm.json")
    private val m = BoundModel.ofJson(perf)
    private val cost = m.num(COST)
    private val subs = m.group("/Perf/Lines/Subs")
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** "more than one (Line, Sub) across ALL Lines shares this Sub's Cost" — the multi-star hash-join path. */
    private val corr = render(cost.filled().and(
        Dsl.moreThanOneFieldFilled(m.flatten("/Perf/Lines*/Subs*/Cost").having(cost.eq(cost.outer())))))

    /** WITH a `CurrentRepetition(Subs)` self-exclusion — "another (Line, Sub) with a DIFFERENT Sub index shares
     *  this Cost" — the naive-fallback path (the exclusion drops every same-Sub-index row, not just the own row). */
    private val corrExcl = render(cost.filled().and(
        Dsl.atLeastOneFieldFilled(m.flatten("/Perf/Lines*/Subs*/Cost").having(
            Dsl.currentRepetition(subs).ne(Dsl.currentRepetition(subs, true)).and(cost.eq(cost.outer()))))))

    private val fields = listOf(
        FieldDef(LINE_KEY, Kind.STRING), FieldDef(SUB_KEY, Kind.STRING), FieldDef(COST, Kind.NUMBER, scale = 0))

    /** (Line, Sub, Cost-or-null) rows → kernel fired count. Throws if the kernel REJECTS the shape (MVK/codegen). */
    private fun kFires(cond: String, rows: List<Triple<Int, Int, String?>>): Int {
        val doc = RuntimeLaws.doc().group("/Perf", 1)
        for ((l, s, c) in rows) {
            doc.group("/Perf/Lines", 1, l).field(LINE_KEY, "L$l", 1, l, 1)
            doc.group("/Perf/Lines/Subs", 1, l, s).field(SUB_KEY, "S${l}_$s", 1, l, s, 1)
            if (c != null) doc.field(COST, c, 1, l, s, 1)
        }
        val model = RuntimeLaws.withRule(perf, "/Perf", "MSC", "../Lines/Subs/Cost", "MSC", cond)
        return RuntimeLaws.messages(model, doc).count { it.code() == "MSC" }
    }

    private fun iFires(cond: String, rows: List<Triple<Int, Int, String?>>): Int {
        val def = ModelDef(fields, listOf(RuleDef("/Perf/MSC", "/Perf", cond, "MSC", "../Lines/Subs/Cost")),
            repeatableGroups = setOf("/Perf/Lines", "/Perf/Lines/Subs"),
            declaredReps = mapOf("/Perf/Lines" to 1000, "/Perf/Lines/Subs" to 1000),
            indexFields = setOf(LINE_KEY, SUB_KEY))
        val doc = def.newDocument()
        for ((l, s, c) in rows) {
            doc.field(LINE_KEY, "L$l", 1, l, 1).field(SUB_KEY, "S${l}_$s", 1, l, s, 1)
            if (c != null) doc.field(COST, c, 1, l, s, 1)
        }
        return DmInterpreter(def).validateFull(doc).count { it.code == "MSC" }
    }

    private fun agree(cond: String, vararg rows: Triple<Int, Int, String?>) =
        assertEquals(kFires(cond, rows.toList()), iFires(cond, rows.toList()), "rows=${rows.toList()}")

    /** The NEW multi-star hash-join path — no self-exclusion. */
    @Test fun crossLineDuplicateCountsAcrossTheWholeMultiStarScope() {
        // Cost 5 at L1/S1 and L2/S1 (cross-Line) → each fires; L1/S2, L2/S2 unique → 2 total.
        agree(corr, t(1, 1, "5"), t(1, 2, "7"), t(2, 1, "5"), t(2, 2, "9"))
        agree(corr, t(1, 1, "5"), t(1, 2, "7"), t(2, 1, "8"), t(2, 2, "9"))   // all distinct → none
        agree(corr, t(1, 1, "5"), t(2, 1, "5"), t(2, 2, "5"))                  // triple duplicate across Lines
    }

    @Test fun emptyKeysAndUnevaluatedRowsDrop() {
        agree(corr, t(1, 1, "5"), t(1, 2, null), t(2, 1, "5"), t(2, 2, null))  // empty Cost rows drop from the buckets
        agree(corr, t(1, 1, null), t(2, 1, "5"), t(2, 2, "5"))                 // the empty outer row is FieldFilled-gated out
        agree(corr, t(1, 1, "0"), t(2, 1, "0"))                                // zero keys — kernel decides; must agree
    }

    /** The naive-fallback path — a multi-star `$`-correlation WITH a leaf-level self-exclusion. */
    @Test fun selfExcludedMultiStarStaysNaiveAndMatchesKernel() {
        agree(corrExcl, t(1, 1, "5"), t(1, 2, "7"), t(2, 1, "5"), t(2, 2, "9"))
        agree(corrExcl, t(1, 1, "5"), t(1, 2, "5"), t(2, 1, "5"), t(2, 2, "9"))
        agree(corrExcl, t(1, 1, "5"), t(2, 1, "5"))
    }

    // Four repeatable levels let the same hash-join path prove both boundary bind depths from IF144's residual:
    // every level starred (bindDepth=0), and only Lines/Bins starred below a bound Network/Region (bindDepth=2).
    private val deepBase = DocumentModelDraft.named("network").locale("en_US")
        .rootGroup("Network") { network ->
            network.repeatable(20).stringField("NetworkKey") { }.indexField("NetworkKey")
            network.group("Regions") { region ->
                region.repeatable(20).stringField("RegionKey") { }.indexField("RegionKey")
                region.group("Lines") { line ->
                    line.repeatable(20).stringField("LineKey") { }.indexField("LineKey")
                    line.group("Bins") { bin ->
                        bin.repeatable(20).stringField("BinKey") { }
                            .numberField("Cost") { it.fractionDigits(0) }.indexField("BinKey")
                    }
                }
            }
        }.build().toJson()
    private val deepModel = BoundModel.ofJson(deepBase)
    private val deepCost = deepModel.num(DEEP_COST)
    private val bindDepth0 = render(deepCost.filled().and(
        Dsl.moreThanOneFieldFilled(
            deepModel.flatten("/Network*/Regions*/Lines*/Bins*/Cost").having(deepCost.eq(deepCost.outer())))))
    private val bindDepth2 = render(deepCost.filled().and(
        Dsl.moreThanOneFieldFilled(
            deepModel.flatten("/Network/Regions/Lines*/Bins*/Cost").having(deepCost.eq(deepCost.outer())))))

    private data class DeepRow(
        val network: Int,
        val region: Int,
        val line: Int,
        val bin: Int,
        val cost: String?,
    )

    private fun deepDocs(def: ModelDef, rows: List<DeepRow>): Pair<RuntimeLaws.Doc, Document> {
        val kernel = RuntimeLaws.doc()
        val interpreter = def.newDocument()
        for ((network, region, line, bin, cost) in rows) {
            kernel.group(NETWORK, network).field(NETWORK_KEY, "N$network", network, 1)
            interpreter.group(NETWORK, network).field(NETWORK_KEY, "N$network", network, 1)
            kernel.group(REGIONS, network, region).field(REGION_KEY, "R$network-$region", network, region, 1)
            interpreter.group(REGIONS, network, region).field(REGION_KEY, "R$network-$region", network, region, 1)
            kernel.group(LINES, network, region, line).field(LINE_KEY_DEEP, "L$line", network, region, line, 1)
            interpreter.group(LINES, network, region, line).field(LINE_KEY_DEEP, "L$line", network, region, line, 1)
            kernel.group(BINS, network, region, line, bin).field(BIN_KEY, "B$bin", network, region, line, bin, 1)
            interpreter.group(BINS, network, region, line, bin).field(BIN_KEY, "B$bin", network, region, line, bin, 1)
            if (cost != null) {
                kernel.field(DEEP_COST, cost, network, region, line, bin, 1)
                interpreter.field(DEEP_COST, cost, network, region, line, bin, 1)
            }
        }
        return kernel to interpreter
    }

    private fun deepAgree(cond: String, expected: Int, rows: List<DeepRow>) {
        val kernelModel = RuntimeLaws.withRule(deepBase, NETWORK, "DEEP", "../Regions/Lines/Bins/Cost", "DEEP", cond)
        val def = ModelLoader.load(kernelModel)
        val (dynamicDoc, interpreterDoc) = deepDocs(def, rows)
        val dynamic = RuntimeLaws.messages(kernelModel, dynamicDoc).count { it.code() == "DEEP" }
        val staticEngine = RuntimeLaws.kernelJavaEngine(kernelModel)
        val staticDoc = deepDocs(def, rows).first
        val static = staticEngine.messages(staticEngine.instance(staticDoc)).count { it.code() == "DEEP" }
        val interpreter = DmInterpreter(def).validateFull(interpreterDoc).count { it.code == "DEEP" }
        assertEquals(expected, dynamic, "Groovy-dynamic expected count")
        assertEquals(dynamic, static, "static-Java must agree at this bind depth")
        assertEquals(dynamic, interpreter, "interpreter must agree at this bind depth")
    }

    @Test fun threePlusLevelCorrelationLocksBindDepthZeroAndTwo() {
        deepAgree(bindDepth0, expected = 2, rows = listOf(
            DeepRow(1, 1, 1, 1, "5"),
            DeepRow(2, 1, 1, 1, "5"), // duplicate only across Network roots: bindDepth=0 must see both
            DeepRow(1, 1, 1, 2, null),
            DeepRow(2, 1, 1, 2, "bad"), // empty and malformed join keys never enter a bucket
        ))
        deepAgree(bindDepth2, expected = 2, rows = listOf(
            DeepRow(1, 1, 1, 1, "7"),
            DeepRow(1, 1, 2, 1, "7"), // same Network/Region, across Lines: both fire
            DeepRow(1, 2, 1, 1, "9"),
            DeepRow(2, 1, 1, 1, "9"), // duplicate only across bound scopes: neither fires
            DeepRow(1, 1, 1, 2, null),
            DeepRow(1, 1, 2, 2, "bad"),
        ))
    }

    private fun t(l: Int, s: Int, c: String?) = Triple(l, s, c)

    private companion object {
        const val LINE_KEY = "/Perf/Lines/LineKey"
        const val SUB_KEY = "/Perf/Lines/Subs/SubKey"
        const val COST = "/Perf/Lines/Subs/Cost"
        const val NETWORK = "/Network"
        const val NETWORK_KEY = "/Network/NetworkKey"
        const val REGIONS = "/Network/Regions"
        const val REGION_KEY = "/Network/Regions/RegionKey"
        const val LINES = "/Network/Regions/Lines"
        const val LINE_KEY_DEEP = "/Network/Regions/Lines/LineKey"
        const val BINS = "/Network/Regions/Lines/Bins"
        const val BIN_KEY = "/Network/Regions/Lines/Bins/BinKey"
        const val DEEP_COST = "/Network/Regions/Lines/Bins/Cost"
    }
}
