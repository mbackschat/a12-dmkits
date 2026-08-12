package io.github.mbackschat.a12.dm.adapter.perf

import java.util.Locale
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Profile mode (INTERPRETER-PERFORMANCE.md): one scenario at **high N**, INTERPRETER-only (no kernel, so a
 * large instance stays cheap and a profiler can attach to the engine alone), verbose per-iteration timing, no
 * timing assertion — this is for **hunting the O(N²) hotspot** in the group machinery, not gating. Opt-in so
 * it never runs in the normal suite:
 *
 * ```
 * ./build.sh :adapter:test --tests "*PerfProfileTest" -Dperf.profile=true -Dperf.n=100000
 * ```
 *
 * `-Dperf.n` sets the scale (default 100k rows). Attach JFR / async-profiler to the JVM to read where the time
 * goes (the prepared instance is reused across iterations, so the timed region is purely `validateFull`).
 */
@EnabledIfSystemProperty(named = "perf.profile", matches = "true")
class PerfProfileTest {

    private val n: Int get() = System.getProperty("perf.n", "100000").toInt()

    @Test
    fun profilePerRow_B1() = profile(PerfCatalog.b1(), n)

    @Test
    fun profileNestedPerSub_B3() = profile(PerfCatalog.b3(), n / PerfCatalog.SUBS_PER_LINE)

    @Test
    fun profileCrossLevel_B4() = profile(PerfCatalog.b4(), n / PerfCatalog.SUBS_PER_LINE)

    /** Profile the COMBINED real-world model (deep+wide+rule-heavy) — the JFR target if its scaling sweep ever
     *  goes super-linear. N = Claim rows (×6 Charge rows each), so `n/50` ≈ 2000 claims at the 100k default. */
    @Test
    fun profileComplexRealWorld() = profile(PerfCatalog.complexRealWorldFull(firing = false), n / 50)

    private fun profile(scenario: PerfScenario, n: Int) {
        val measurement = PerfHarness.measurementLabel(scenario, n)
        PerfHarness.assertSemanticAgreement(scenario, listOf(n), withJava = true)
        val prep = InterpreterPerfEngine.build(scenario).instance(n)
        val firings = prep.run().size   // also the correctness payload, though profile mode does not gate
        println("[profile] $measurement ${scenario.name} N=$n firings=$firings — warming…")
        repeat(WARMUP) { prep.run() }
        val times = DoubleArray(ITERS) {
            val t = System.nanoTime(); prep.run(); (System.nanoTime() - t) / 1e6
        }
        times.forEachIndexed { i, ms -> println("  $measurement iter ${i + 1}: ${"%.1f".format(Locale.ROOT, ms)}ms") }
        val sorted = times.sorted()
        println("[profile] $measurement ${scenario.name} N=$n  median=${"%.1f".format(Locale.ROOT, sorted[ITERS / 2])}ms " +
            "min=${"%.1f".format(Locale.ROOT, sorted.first())}ms max=${"%.1f".format(Locale.ROOT, sorted.last())}ms")
    }

    private companion object {
        const val WARMUP = 8
        const val ITERS = 12
    }
}
