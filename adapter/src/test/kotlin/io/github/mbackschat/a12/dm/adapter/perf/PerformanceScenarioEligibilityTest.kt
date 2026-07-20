package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag

/**
 * Kernel-backed semantic preflight for the shared JVM/Node/native performance fixtures. The high-N workers
 * retain exact interpreter outcomes; this test proves the same typed scenario definitions against both kernel
 * strategies at representative scales before any process lane is eligible to run.
 */
@Tag("perf")
class PerformanceScenarioEligibilityTest {

    @Test
    fun sharedScenariosAgreeWithBothKernelStrategies() {
        listOf(
            PerfCatalog.manyRules(48, firing = false) to listOf(100),
            PerfCatalog.manyRules(192, firing = false) to listOf(100),
            PerfCatalog.b1() to listOf(2_500, 10_000),
            PerfCatalog.b2() to listOf(2_500, 10_000),
        ).forEach { (scenario, scales) ->
            PerfHarness.assertSemanticAgreement(scenario, scales, withJava = true)
        }
    }
}
