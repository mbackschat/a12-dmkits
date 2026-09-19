package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.ProfileSynthesisTargetProfiles
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The checker says nothing about a SYNTHESIZED model two orders of magnitude larger than any committed one.
 *
 * **Why a fourth silence population, when three already exist.** [CheckerSilenceOnCleanModelsTest] and
 * [CheckerSilenceOnMutatedModelsTest] measure the committed trees, whose models are hand-authored and small —
 * the largest carries a few dozen declarations. Every family in the checker was therefore written against
 * models of one size, and "it over-reports only above some declaration count" is a shape none of those sweeps
 * can see. This one is generated at 200 and 800 programs, so a defect that needs scale to appear has
 * somewhere to appear.
 *
 * **Valid by construction, then verified anyway.** The model is built through the typed kits, which validate
 * each rule and computation as they are authored, so it should be A12-valid — but "should be" is not an
 * oracle, and a checker asserted silent on a model A12 actually rejects would be measuring nothing. So the
 * Kernel is asked first, through `BoundModel.ofJson`, which is `checkConsistency` with no ERROR; only then is
 * the checker's silence a claim about the checker.
 *
 * **It reuses the profile benchmark's own synthesizer rather than growing a second one**
 * ([ProfileSynthesisTargetProfiles.modelJson]), which is what keeps this a population rather than a fixture
 * estate. The `N`/`4N` pair is the shape H5's growth work needs anyway
 * ([the plan's H5 partition](../../../../../../../../../../../docs/archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ordering)),
 * and asserting correctness over it now is what makes those later lanes measure a checker that is right.
 *
 * **No timing here, deliberately.** This is the semantic preflight H5 requires before any number is taken,
 * not a benchmark: it takes no clock, declares no budget, and belongs to the guard lane rather than to a perf
 * lane that needs the machine to itself.
 */
class CheckerSilenceOnSynthesizedModelsTest {

    /**
     * The `N`/`4N` pair, small enough that the guard lane stays quick and large enough to leave every
     * committed model far behind: 200 programs is 200 rules plus 200 computations over 400 fields.
     */
    private val scales = listOf(200, 800)

    @Test
    fun theCheckerIsSilentOnASynthesizedModelAtBothScales() {
        val reported = mutableMapOf<String, List<String>>()
        scales.forEach { programs ->
            val modelJson = ProfileSynthesisTargetProfiles.modelJson(programs)
            assertTrue(
                runCatching { BoundModel.ofJson(modelJson) }.isSuccess,
                "A12 must BUILD the synthesized model at $programs programs before the checker's silence " +
                    "about it means anything — otherwise this asserts agreement with a rejection",
            )
            reported["$programs programs"] = check(ModelSource(modelJson, diagnosticLabel = "synth-$programs"))
                .findings
                .filter { it.justifiesRejection }
                .map { "${it.code} at ${it.at}" }
                .sorted()
        }

        assertEquals(
            scales.associate { "$it programs" to emptyList<String>() }, reported,
            "the checker refused a model A12 builds. This population exists to catch a family whose " +
                "over-report needs SCALE to appear, which no committed model is large enough to expose — so " +
                "a failure here names a real over-rejection rather than a fixture problem",
        )
    }
}
