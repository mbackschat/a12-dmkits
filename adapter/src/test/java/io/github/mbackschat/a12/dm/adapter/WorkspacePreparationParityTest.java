package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.preparation.WorkspacePreparationFixtures;

/**
 * R2.1's reusable preparation-oracle lock: every typed-authored raw workspace is observed through the real
 * Kernel expander and the JVM {@code ModelReader}, then compared through one representation-neutral surface.
 */
class WorkspacePreparationParityTest {

    @Test
    void workspaceMatrixMatchesKernelExpansion() {
        for (var fixture : WorkspacePreparationFixtures.cases()) {
            var observation = PreparationParityHarness.observe(fixture.entryJson(), fixture.resolver());

            PreparationParityHarness.assertEquivalent(observation);
            assertEquals(fixture.expectedFields(), observation.reader().fieldPaths(),
                    () -> fixture.id() + " reader fields");
            assertEquals(fixture.expectedRules(), observation.reader().rules(),
                    () -> fixture.id() + " reader rules");
            assertEquals(fixture.expectedComputations(), observation.reader().computations(),
                    () -> fixture.id() + " reader computations");
        }
    }

    @Test
    void negativeControlProvesTheComparatorFires() {
        var fixture = WorkspacePreparationFixtures.caseById("self-contained");
        var observation = PreparationParityHarness.observe(fixture.entryJson(), fixture.resolver());
        var firstPath = observation.reader().elements().keySet().iterator().next();
        var perturbed = observation.withReader(observation.reader().withoutElement(firstPath));

        assertThrows(AssertionError.class, () -> PreparationParityHarness.assertEquivalent(perturbed),
                "a one-sided missing element must make the preparation comparator fail");
    }
}
