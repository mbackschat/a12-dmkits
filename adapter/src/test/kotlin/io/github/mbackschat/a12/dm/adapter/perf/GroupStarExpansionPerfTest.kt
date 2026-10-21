package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Perf gate — a starred GROUP operand whose expansion runs on the `expandGroupStars` path stays **linear** in
 * rows ([IG136]'s last residual).
 *
 * **Why this class exists beside `GroupScopedFillPerfTest`, which looks like it already covers this.** It does
 * not, and the difference is the whole point: `AtLeastOneFieldFilled(/Order/Items*)` is dispatched onto
 * `EvalContext.fillScope`, a group-aware scope call that never consults `expandGroupStars`. The gap was once
 * closed on exactly that misreading — the closure named a path no scenario executed — so this scenario picks
 * `NumberOfFilledFields(/Order/Items*)` instead, whose `filledFieldsValue` opens with
 * `for (a in expandGroupStars(args))`. Two carriers, two resolution paths, and only one of them was measured.
 *
 * **How the class proves the path is REACHED rather than merely running**, which is the assertion the earlier
 * closure lacked: `expandGroupStars` is what turns the group operand into its contained fields, so an engine
 * that skips it reads cells at a path holding no value and counts **zero** — [IF287]'s fifth instance, measured.
 * The rule's bar is therefore `> 0`, and `assertSemanticAgreement` against both kernel strategies is itself the
 * evidence: a non-expanding interpreter answers `0`, does not fire, and disagrees at both sizes. The tri-engine
 * eligibility check is the reachability proof, not a preamble to it.
 *
 * **The group holds FOUR fields on purpose.** `expandGroupStars` expands per evaluation and its own cost is
 * O(declared fields); with a single field the expansion is invisible next to the row scan, so the scenario
 * would measure the star iteration and report nothing about the expansion it was written for.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupStarExpansionPerfTest {

    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { o ->
            o.stringField("CustomerName") { }
            o.group("Items") { g ->
                g.repeatable(MAX)
                    .stringField("Sku") { }
                    .stringField("Batch") { }
                    .stringField("Bin") { }
                    .stringField("Note") { }
            }
        }.build().toJson()
    private val m = BoundModel.ofJson(base)

    // FieldFilled(CustomerName) And NumberOfFilledFields(/Order/Items*) > 0 — the count routes expandGroupStars.
    private val cond = DslRenderer().render(
        m.field(CN).filled().and(Dsl.numberOfFilledFields(m.flattenGroup("/Order/Items*")).gt(0)))

    private val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../CustomerName", "GSE", cond)
    private val def: ModelDef = ModelLoader.load(kModel)
    private val scenario = PerfScenario("group-star-expansion", kModel, def) { sink, n, _ ->
        sink.group("/Order", 1)
        sink.field(CN, "Acme", 1, 1)
        for (r in 1..n) {
            sink.group("/Order/Items", 1, r)
            sink.field(SKU, "S$r", 1, r, 1)
            sink.field(BATCH, "B$r", 1, r, 1)
            sink.field(BIN, "N$r", 1, r, 1)
            sink.field(NOTE, "T$r", 1, r, 1)
        }
    }

    /**
     * Eligibility AND the reachability proof in one: agreement with both kernel strategies at both sizes is only
     * possible if the group operand was expanded, since the unexpanded read counts zero and the bar never fires.
     */
    @BeforeAll
    fun assertExactTriEngineEligibility() {
        PerfHarness.assertSemanticAgreement(scenario, listOf(N, N * 4), withJava = true)
    }

    @Test
    fun starredGroupExpansionScalesSubQuadratically() {
        PerfHarness.assertSubQuadraticLane(
            label = "group-star expansion",
            small = N,
            identityAt = { n -> PerfHarness.measurementLabel(scenario, n) },
            culprit = "re-expanding the starred group operand per row instead of once per evaluation",
            clock = "cpu",
            unit = "rows",
            batch = NOISE_BATCH,
        ) { PerfHarness.timeInterpreterCpu(scenario, it, warmup = 5, iters = 5, batch = NOISE_BATCH) }
    }

    private companion object {
        const val NOISE_BATCH = 3
        const val N = 2000
        const val MAX = 200_000   // far above 4*N, so the kernel never treats a row as over-limit context
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val BATCH = "/Order/Items/Batch"
        const val BIN = "/Order/Items/Bin"
        const val NOTE = "/Order/Items/Note"
    }
}
