package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.checkDraft
import io.github.mbackschat.a12.dm.consistency.snapshotOf
import io.github.mbackschat.a12.dm.consistency.check as runChecker
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-e: the AFTER-EDIT boundary — what holding one analysis across many questions saves, measured.
 *
 * **Exactly ONE of its three arms carries a bar, and the other two never will.** H5 requires a budget to be
 * ratified before the implementation it gates is measured, and the owner's 2026-09-16 ratification left this
 * boundary UNSET because nothing existed to measure
 * (`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification`). L-a then shipped the operations, the
 * collector ran, and the **keystroke** ratio was ratified on 2026-09-17 against [KEYSTROKE_CEILING] — whose
 * basis is a work count written before this lane existed, never a figure this lane produced, because asserting
 * a bar derived from a collector's own output is what H5's ordering rule forbids.
 *
 * The **build-snapshot** and **saved-edit** arms stay informative permanently, and that is a result rather than
 * a gap: both sit at ~1.0 BY CONSTRUCTION — building an analysis contains one check, and `withSource`
 * re-prepares and re-checks because the source set changed — so a ceiling on either would have to come from
 * this lane's own spread, and the saved-edit arm straddles 1.0 across four runs anyway (0.958-1.006x), so such
 * a bar would refuse healthy runs. Their figures live in
 * `docs/PORTABLE-CONSISTENCY-RESULTS.md#after-edit-2026-09-16`.
 *
 * **Why a RATIO against the stateless path rather than a latency.** `ConsistencySnapshot`'s claim is about
 * work avoided, not about wall time, and [io.github.mbackschat.a12.dm.consistency.Reuse] already counts the
 * work. What a counter cannot say is whether avoiding that work is worth holding the analysis, so this lane
 * measures the same question a consumer asks: the stateful call against the stateless one that answers
 * identically. Both terms are timed in the same run on the same machine and INTERLEAVED per pair, so drift
 * over the run lands in both and the median of within-pair ratios survives it — the estimator H5-d adopted
 * for the cold lane, for the same reason.
 *
 * **The two preflights are not ceremony.** A model the checker REJECTS would have this lane compare the cost
 * of two rejection paths, and two calls that disagree would make the ratio a comparison of different answers
 * rather than of two ways to reach one. The agreement preflight is also the warm-up, outside every clock.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.afteredit", matches = "true")
class CheckerAfterEditPerfTest {

    @Test
    fun whatHoldingOneAnalysisSavesOnAKeystrokeAndOnASavedEdit() {
        JdkIdentity.requireSupported()
        val programs = requiredProperty("perf.checker.afteredit.n").toInt()
        val samples = requiredProperty("perf.checker.afteredit.samples").toInt()
        require(programs > 0) { "perf.checker.afteredit.n must be positive" }

        val text = ProfileSynthesisTargetProfiles.modelJson(programs)
        val source = ModelSource(text, diagnosticLabel = "after-edit")
        val baseline = runChecker(source)
        require(baseline.findings.none { it.justifiesRejection }) {
            "the checker REFUSED the synthesized model at $programs programs, so this lane would compare the " +
                "cost of rejecting rather than of checking: " +
                baseline.findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }.sorted()
        }
        // Null would mean the source declared no id and so was never admitted, which the rejection preflight
        // above cannot see — it reads findings, and an unadmitted source produces diagnostics instead.
        val entry = checkNotNull(baseline.modelId) { "the synthesized model declares no id, so it was never admitted" }
        // A silent no-op here would leave the saved-edit arm comparing the model against itself, which is the
        // one way this lane could report a reuse figure for an edit that never happened.
        val editedText = text.replace(FIRST_CONDITION, DRAFTED_CONDITION)
        require(editedText != text) {
            "the synthesized model does not spell its first condition as `$FIRST_CONDITION`, so the saved " +
                "edit is a no-op and every figure below would describe an unchanged model"
        }
        val edited = ModelSource(editedText, "after-edit-saved")

        // Warm-up and agreement in one pass: a snapshot answer that differed from the stateless one would
        // make every ratio below a comparison of two different answers.
        val snapshot = snapshotOf(listOf(source), entry)
        require(snapshot.draft(AT, MEMBER, DRAFTED_CONDITION).edit == checkDraft(source, AT, MEMBER, DRAFTED_CONDITION)) {
            "the snapshot and the stateless draft disagree, so no ratio between them is meaningful"
        }
        require(snapshot.withSource(edited).report == runChecker(edited)) {
            "an updated snapshot disagrees with a fresh check of the updated set, so no ratio is meaningful"
        }

        // The one ARM of this lane that carries a bar, and it is asserted rather than printed. Measured first, as
        // it always was, so the ratified figure is not the one that inherited two other arms' warm-up.
        val keystroke = PerfHarness.assertReuseRatioBelowCeiling(
            "a keystroke against the stateless draft at $programs programs",
            ceiling = KEYSTROKE_CEILING,
        ) {
            pairedRatio(
                samples,
                stateless = { millis { checkDraft(source, AT, MEMBER, DRAFTED_CONDITION).introduced.size } },
                stateful = { millis { snapshot.draft(AT, MEMBER, DRAFTED_CONDITION).edit.introduced.size } },
            )
        }
        val savedEdit = pairedRatio(
            samples,
            stateless = { millis { runChecker(edited).findings.size } },
            stateful = { millis { snapshot.withSource(edited).report.findings.size } },
        )
        // Paired like the other two rather than timed on its own: an unpaired absolute measured last, after
        // everything above has warmed the JIT, is not comparable to the arms printed beside it — and read as
        // if it were, it says building an analysis is cheaper than the check it contains.
        val building = pairedRatio(
            samples,
            stateless = { millis { runChecker(source).findings.size } },
            stateful = { millis { snapshotOf(listOf(source), entry).report.findings.size } },
        )

        PerfHarness.progress(
            "[checker-after-edit] programs=$programs samples=$samples " +
                "build-snapshot stateless-check=${format(building.statelessMs)} -> " +
                "snapshot=${format(building.statefulMs)} ms paired-ratio=${format(building.ratio)}x | " +
                "keystroke stateless=${format(keystroke.statelessMs)} -> snapshot=${format(keystroke.statefulMs)} ms " +
                "paired-ratio=${format(keystroke.ratio)}x | " +
                "saved-edit stateless=${format(savedEdit.statelessMs)} -> snapshot=${format(savedEdit.statefulMs)} ms " +
                "paired-ratio=${format(savedEdit.ratio)}x",
        )
    }

    /** Medians of both arms and the median of the within-pair ratios, the two arms interleaved per pair. */
    private fun pairedRatio(samples: Int, stateless: () -> Double, stateful: () -> Double): Reuse {
        val statelessMs = DoubleArray(samples)
        val statefulMs = DoubleArray(samples)
        val ratios = DoubleArray(samples)
        repeat(samples) { pair ->
            statelessMs[pair] = stateless()
            statefulMs[pair] = stateful()
            ratios[pair] = statefulMs[pair] / statelessMs[pair]
        }
        return Reuse(median(ratios), median(statelessMs), median(statefulMs))
    }

    /**
     * One call, clocked.
     *
     * [read] returns something taken off the result for the same reason the growth lane counts findings
     * inside its timed region: an analysis whose result nothing reads is the one thing a timing lane must
     * never measure.
     */
    private fun millis(read: () -> Int): Double {
        val started = System.nanoTime()
        val observed = read()
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        require(observed >= 0) { "unreachable, and the reason the result is read at all" }
        return elapsed
    }

    private fun median(values: DoubleArray): Double = values.sortedArray().let { it[it.size / 2] }

    private fun format(value: Double): String = "%.3f".format(Locale.ROOT, value)

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }

    private companion object {
        /**
         * The keystroke arm's ratified ceiling, and its basis is a WORK COUNT written before this lane existed.
         *
         * `checkDraft` performs one admission, one preparation and TWO checks per call; a snapshot makes the same
         * keystroke cost one check and nothing else. That count is L-a's stated reason to exist
         * (`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#l-partition`), so a ratio at or above `1.0` means the
         * held analysis saves nothing and an editor has no reason to hold one — the feature, not a measurement,
         * is what fixes the number.
         *
         * **Deliberately NOT tightened toward the measured 0.474-0.502x** (eight independent estimates spanning
         * 5.9 %, 2026-09-17, `docs/PORTABLE-CONSISTENCY-RESULTS.md#after-edit-2026-09-16`). The work count
         * predicts ~0.5, so a bar
         * near it would be fitted to the implementation it gates, which is the failure IF137's promotion gate
         * exists to prevent; this is a catastrophe floor in the ratified house style, with ~2x of headroom, the
         * same posture as `PerfBounds.SUB_QUADRATIC_MAX` at 9.0 against a measured 5.33x.
         */
        const val KEYSTROKE_CEILING = 1.0

        /** The first synthesized rule, whose condition is the text an author would be typing into. */
        const val AT = "/Calibration/RequireInput00000"
        const val MEMBER = "errorCondition"
        const val FIRST_CONDITION = "FieldNotFilled(Input00000)"
        const val DRAFTED_CONDITION = "FieldNotFilled(Input00000) And FieldFilled(Input00001)"
    }
}
