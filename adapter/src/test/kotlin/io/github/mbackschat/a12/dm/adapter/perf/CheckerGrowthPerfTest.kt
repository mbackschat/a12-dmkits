package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check as runChecker
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * H5-c: how the Kernel-free checker's PREPARED cost grows with a model's declarations, and the guard that
 * keeps the 2026-09-15 cubic from coming back.
 *
 * **The growth statistic is the claim; there is no absolute bar.** H5 requires a budget to be ratified before
 * the implementation it gates is measured and no checker latency is ratified, so nothing here asserts a
 * duration. What it does assert is the estate's already-ratified **growth** mechanism —
 * `PerfBounds.SUB_QUADRATIC_MAX`, which sits between a linear N/4N ratio of 4× and a quadratic 16× — through
 * the same paired estimator every other growth lane uses. A ratio is not a budget: it survives a faster
 * machine, a slower one, and a JIT change, which is exactly why it is the thing that can be locked today.
 *
 * **Why this lane is a guard where the cold one is only a record.** `CheckerColdStartPerfTest` measured the
 * checker at 8.6 s for 800 rules plus 800 computations and the profile named the cause: a whole-model
 * declaration walk reached from a per-element path, so the whole check was cubic in declarations
 * ([both curves](../../../../../../../../../../../docs/PORTABLE-CONSISTENCY-RESULTS.md#hoisted-2026-09-15)).
 * That fix is one hoisted value with nothing structural stopping the next one from being un-hoisted, and no
 * correctness test can see it — a cubic checker gives exactly the right answers. A growth floor is the control
 * for that class rather than for the one instance of it.
 *
 * **Prepared, not cold: the model TEXT is built and the checker is warmed outside every clock.** Typed
 * authoring through the kits costs more than the check does and is not the subject, and a first call carries
 * class loading and the serializer's reflective setup — which is the COLD boundary and the other lane's
 * question. Measuring them here would report one number for two things.
 */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.checker.growth", matches = "true")
class CheckerGrowthPerfTest {

    @Test
    fun thePreparedCheckerGrowsBelowTheCatastropheFloorFromNToFourN() {
        JdkIdentity.requireSupported()
        val programs = requiredProperty("perf.checker.growth.n").toInt()
        val pairs = requiredProperty("perf.checker.growth.pairs").toInt()
        require(programs > 0) { "perf.checker.growth.n must be positive" }
        val scales = listOf(programs, programs * 4)
        val texts = scales.associateWith { ProfileSynthesisTargetProfiles.modelJson(it) }

        // The preflight IS the warm-up, which is why it runs a full check at each scale rather than a cheaper
        // probe: the class loading and reflective setup a first call pays for belong to the cold lane's
        // question, and a ratio taken over a model the checker refuses would be a ratio over the rejection
        // path. One pass settles both, outside every clock.
        texts.forEach { (scale, text) ->
            val rejecting = rejectingCodes(text, "growth-$scale")
            require(rejecting.isEmpty()) {
                "the checker REFUSED the synthesized model at $scale programs, so this lane would compare the " +
                    "cost of rejecting rather than of checking: $rejecting"
            }
        }

        PerfHarness.assertGrowthBelowCatastropheFloor(
            "portable checker N/4N over model declarations",
            "a whole-model declaration walk reached from a per-element path — the shape that made this check " +
                "cubic in 2026-09 and is invisible to every correctness test",
        ) {
            PerfHarness.pairedGrowth(scales.first(), scales.last(), pairs) { scale ->
                timedCheckMillis(checkNotNull(texts[scale]) { "no model text for $scale programs" })
            }.also { growth ->
                PerfHarness.progress(
                    "[checker-growth] programs=${scales.first()}->${scales.last()} " +
                        "median=${format(growth.smallValue)}->${format(growth.largeValue)} ms " +
                        "paired-ratio=${format(growth.ratio)}x",
                )
            }
        }
    }

    /**
     * One prepared check, clocked.
     *
     * The report's findings are counted inside the timed region on purpose: it is the one cheap way to keep the
     * whole analysis from being provably dead code, and a checker whose result nothing reads is the one thing a
     * timing lane must never measure.
     */
    private fun timedCheckMillis(text: String): Double {
        val started = System.nanoTime()
        val findings = runChecker(ModelSource(text, diagnosticLabel = "growth")).findings.size
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        require(findings >= 0) { "unreachable, and the reason the count is read at all" }
        return elapsed
    }

    private fun rejectingCodes(text: String, label: String): List<String> =
        runChecker(ModelSource(text, diagnosticLabel = label))
            .findings
            .filter { it.justifiesRejection }
            .map { "${it.code}@${it.at}" }
            .sorted()

    private fun format(value: Double): String = "%.2f".format(Locale.ROOT, value)

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "-D$name=<value> is required" }
}
