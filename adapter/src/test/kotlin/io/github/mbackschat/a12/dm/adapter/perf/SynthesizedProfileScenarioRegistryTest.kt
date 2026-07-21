package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SynthesizedProfileScenarioRegistryTest {

    @Test
    fun coversEveryCommittedStressShapeWithAnExecutableScaleAxis() {
        assertEquals(
            listOf(
                SynthesizedProfileScenario.COMPACT,
                SynthesizedProfileScenario.BREADTH_SHAPED,
                SynthesizedProfileScenario.DEPTH_SHAPED,
            ),
            SynthesizedProfileScenario.all,
        )
        assertEquals(
            mapOf(
                "compact.json" to listOf("/G1/G2/G3/G4/G5/G6", "PAIRWISE", "100"),
                "breadth-shaped.json" to listOf("/G1/G17/G18/G19", "LINEAR", "100"),
                "depth-shaped.json" to listOf("/G1/G7/G8/G9/G10/G11", "LINEAR", "100"),
            ),
            SynthesizedProfileScenario.all.associate { profile ->
                profile.fileName to listOf(
                    profile.leafGroup,
                    profile.complexity.name,
                    profile.defaultSmallScale.toString(),
                )
            },
        )
    }

    @Test
    fun selectionRejectsAnUnknownProfileInsteadOfFallingBack() {
        assertThrows(IllegalArgumentException::class.java) {
            SynthesizedProfileScenario.named("unknown")
        }
    }
}
