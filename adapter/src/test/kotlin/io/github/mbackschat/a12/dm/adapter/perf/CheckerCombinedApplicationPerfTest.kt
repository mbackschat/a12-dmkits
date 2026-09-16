package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.snapshotOf
import io.github.mbackschat.a12.dm.consistency.check as runChecker
import io.github.mbackschat.a12.dm.interpreter.PreparedModel as InterpreterPreparedModel
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5's COMBINED boundary — what an application pays for choosing consistency *and* evaluation.
 *
 * **The quantity is ADDITIVITY, not latency, and the proposal fixes it rather than this lane choosing it.**
 * `docs/PORTABLE-CONSISTENCY-PROPOSAL.md` §4 says an
 * application choosing both "may hold two prepared representations and pay for two parses. Measure that
 * combined cost separately; no zero-copy integration, shared-program adapter or elimination of all
 * cross-component duplicate parsing is promised." So the question a consumer actually has is not "how many
 * milliseconds" — no checker duration is ratified and none is asserted anywhere — but **whether holding both
 * costs the sum of holding each**. That is a ratio, it survives a faster or slower machine, and it is the one
 * thing that would change if a future shared-parse optimization landed or if the two preparations began
 * interfering.
 *
 * **Both representations really are separate types, which is why the cost is expected to add up.**
 * `:consistency` prepares a `ConsistencySnapshot` and `:interpreter` prepares its own `PreparedModel` — two
 * classes that share a name across two modules, imported here under an alias for exactly that reason. Neither
 * reads the other's parse. A measured ratio near `1.0` therefore CONFIRMS the proposal's stated non-promise
 * rather than exposing a defect; a ratio meaningfully above it would mean the two preparations interfere
 * (allocation pressure, GC), which is the finding this lane exists to be able to see.
 *
 * **It records and asserts nothing.** H5 requires a budget to be ratified before the implementation it gates
 * is measured, and this boundary is explicitly UNSET in the owner's 2026-09-16 ratification
 * (`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification`) because F's package had to exist first.
 * Its output is informative until per-number ratified, which is the sequence that record prescribes; a bar
 * taken from this lane's own first figure is what H5's ordering rule forbids. Deliberately NOT wired into any
 * automated lane, because a schedule implies a bar — the same posture the cold lane held before H5-d.
 *
 * **The three preflights are not ceremony.** A model the checker REJECTS would have the consistency arm time a
 * rejection path; a model the interpreter cannot PREPARE would leave the evaluation arm timing an exception;
 * and two arms preparing different entry models would make the sum meaningless. All three run outside every
 * clock and double as the warm-up.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.combined", matches = "true")
class CheckerCombinedApplicationPerfTest {

    @Test
    fun whatAnApplicationPaysForHoldingBothPreparedRepresentations() {
        JdkIdentity.requireSupported()
        val programs = requiredProperty("perf.checker.combined.n").toInt()
        val samples = requiredProperty("perf.checker.combined.samples").toInt()
        require(programs > 0) { "perf.checker.combined.n must be positive" }
        // Odd, because the estimator medians within-pair ratios and an even count would report the midpoint
        // of two observed ratios rather than one of them — the reason the sibling lanes default to 11.
        require(samples % 2 == 1) { "perf.checker.combined.samples must be odd so the median is an observation" }

        val text = ProfileSynthesisTargetProfiles.modelJson(programs)
        val source = ModelSource(text, diagnosticLabel = "combined")

        val baseline = runChecker(source)
        require(baseline.findings.none { it.justifiesRejection }) {
            "the checker REFUSED the synthesized model at $programs programs, so the consistency arm would " +
                "time a rejection rather than a check: " +
                baseline.findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }.sorted()
        }
        // Null would mean the source declared no id and so was never admitted, which the rejection preflight
        // above cannot see — it reads findings, and an unadmitted source produces diagnostics instead.
        val entry = checkNotNull(baseline.modelId) { "the synthesized model declares no id, so it was never admitted" }

        val prepared = InterpreterPreparedModel.loadExpandedJson(text)
        require(prepared.entryModelId == entry) {
            "the two modules prepared different entry models from one text — consistency read '$entry' and " +
                "the interpreter read '${prepared.entryModelId}' — so their costs are not two halves of one " +
                "application's work and summing them would describe nothing"
        }

        // BOTH arm orders, in one run, because the comparison is the control — see `additivity`. Reporting one
        // order alone would leave a few percent of warm-up indistinguishable from real sub-additivity.
        val bothLast = additivity(samples, source, entry, text, bothFirst = false)
        val bothFirst = additivity(samples, source, entry, text, bothFirst = true)
        PerfHarness.progress(
            "[checker-combined] programs=$programs samples=$samples " +
                "both-last consistency=${format(bothLast.consistencyMs)} evaluation=${format(bothLast.evaluationMs)} " +
                "sum=${format(bothLast.consistencyMs + bothLast.evaluationMs)} both=${format(bothLast.bothMs)} ms " +
                "additivity-ratio=${format(bothLast.ratio)}x | " +
                "both-first consistency=${format(bothFirst.consistencyMs)} " +
                "evaluation=${format(bothFirst.evaluationMs)} " +
                "sum=${format(bothFirst.consistencyMs + bothFirst.evaluationMs)} both=${format(bothFirst.bothMs)} ms " +
                "additivity-ratio=${format(bothFirst.ratio)}x",
        )
    }

    /**
     * Medians of the three arms and the median of the within-pair additivity ratios.
     *
     * The three arms are INTERLEAVED per pair rather than run in three blocks, so drift over the run lands in
     * all of them and the median of within-pair ratios survives it — the estimator H5-d adopted for the cold
     * lane, for the same reason. The ratio is formed per pair, never from the three medians, because a
     * quotient of medians is not the median of quotients.
     */
    private fun additivity(
        samples: Int,
        source: ModelSource,
        entry: String,
        text: String,
        bothFirst: Boolean,
    ): Combined {
        val consistencyMs = DoubleArray(samples)
        val evaluationMs = DoubleArray(samples)
        val bothMs = DoubleArray(samples)
        val ratios = DoubleArray(samples)
        repeat(samples) { pair ->
            // ARM ORDER IS A CONTROLLED VARIABLE, not an implementation detail. With a fixed order the last
            // arm inherits the others' warm-up, which is enough to move the ratio a few percent and would be
            // reported as sub-additivity — a finding the proposal explicitly does not promise, manufactured by
            // the harness. Running the same lane both ways and comparing is what separates the two.
            if (bothFirst) {
                bothMs[pair] = millis { both(source, entry, text) }
                consistencyMs[pair] = millis { consistencyOnly(source, entry) }
                evaluationMs[pair] = millis { evaluationOnly(text) }
            } else {
                consistencyMs[pair] = millis { consistencyOnly(source, entry) }
                evaluationMs[pair] = millis { evaluationOnly(text) }
                bothMs[pair] = millis { both(source, entry, text) }
            }
            ratios[pair] = bothMs[pair] / (consistencyMs[pair] + evaluationMs[pair])
        }
        return Combined(median(consistencyMs), median(evaluationMs), median(bothMs), median(ratios))
    }

    private data class Combined(
        val consistencyMs: Double,
        val evaluationMs: Double,
        val bothMs: Double,
        val ratio: Double,
    )

    /** The checker's prepared representation, with its report read so the preparation cannot be elided. */
    private fun consistencyOnly(source: ModelSource, entry: String): Int =
        snapshotOf(listOf(source), entry).report.findings.size

    /** The interpreter's prepared representation; `fingerprint` is the cheap read that forces the load. */
    private fun evaluationOnly(text: String): Int =
        InterpreterPreparedModel.loadExpandedJson(text).fingerprint.length

    /**
     * Both, held at once — which is the arm that would differ from the sum if they interfered.
     *
     * Both handles stay live until the sum is returned, because an application that holds one and discards the
     * other is not the case §4 describes and would let the collector measure a cheaper thing than the claim.
     */
    private fun both(source: ModelSource, entry: String, text: String): Int {
        val snapshot = snapshotOf(listOf(source), entry)
        val prepared = InterpreterPreparedModel.loadExpandedJson(text)
        return snapshot.report.findings.size + prepared.fingerprint.length
    }

    /**
     * One call, clocked.
     *
     * [read] returns something taken off the result for the same reason the growth lane counts findings inside
     * its timed region: a preparation whose result nothing reads is the one thing a timing lane must never
     * measure.
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
}
