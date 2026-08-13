package io.github.mbackschat.a12.dm.adapter.perf

import org.junit.jupiter.api.Test

/**
 * Kernel-backed semantic preflight for the shared JVM/Node/native performance fixtures. The high-N workers
 * retain exact interpreter outcomes; this test proves the same typed scenario definitions against both kernel
 * strategies at representative scales before any process lane is eligible to run.
 *
 * **Not a performance test, so it carries no perf tag** (2026-08-13): it asserts SEMANTIC agreement and reads no
 * clock, which is what makes it safe under the broad suite's parallel forks — contention can change how long an
 * agreement check takes but never its verdict. It used to be `@Tag("perf")`, which excluded it from the ordinary
 * lane and left it running only under the opt-in `:interpreter:interpreterPerformance` process lane whose
 * precondition it is. It is now in both: `:adapter:performanceEligibilityTest` remains that lane's gate, and the
 * ordinary suite pays ~20 s for it on every push.
 */
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
