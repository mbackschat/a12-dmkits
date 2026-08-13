package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.profile.ModelSynthesizer
import java.util.Locale
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Opt-in catastrophe guard for the profile synthesizer's program-population write path. */
@Tag("perf-bench")
@EnabledIfSystemProperty(named = "perf.profile.synthesis", matches = "true")
class ProfileSynthesisPerfTest {

    @Test
    fun typedProgramPopulationStaysSubQuadratic() {
        val small = System.getProperty("perf.profile.synthesis.n", "32").toInt()
        val large = Math.multiplyExact(small, 4)
        val pairs = System.getProperty(
            "perf.profile.synthesis.pairs",
            PerfBounds.GROWTH_PAIRS.toString(),
        ).toInt()
        require(small > 0) { "perf.profile.synthesis.n must be positive" }
        require(pairs > 0 && pairs % 2 == 1) {
            "perf.profile.synthesis.pairs must be a positive odd number"
        }

        // Target authoring and profile extraction are deliberately outside every synthesis clock.
        val profiles = listOf(small, large).associateWith(ProfileSynthesisTargetProfiles::extract)
        profiles.values.forEach { profile -> check(ModelSynthesizer.synthesize(profile, SEED).isNotBlank()) }

        PerfHarness.assertGrowthBelowCatastropheFloor(
            "profile synthesis", "per-program whole-model rewrites regressing toward quadratic growth",
        ) {
            PerfHarness.pairedGrowth(small, large, pairs) { programsPerKind ->
                timedSynthesisMillis(checkNotNull(profiles[programsPerKind]))
            }.also { growth ->
                PerfHarness.progress(
                    "[profile-synthesis] rules+computations=$small+$small->$large+$large " +
                        "median=${format(growth.smallMs)}->${format(growth.largeMs)} ms " +
                        "paired-ratio=${format(growth.ratio)}x",
                )
            }
        }
    }

    private fun timedSynthesisMillis(profile: String): Double {
        val started = System.nanoTime()
        val model = ModelSynthesizer.synthesize(profile, SEED)
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        check(model.isNotBlank()) { "profile synthesis returned an empty model" }
        return elapsed
    }

    private fun format(value: Double): String = "%.2f".format(Locale.ROOT, value)

    private companion object {
        const val SEED = 20260721L
    }
}
