package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import io.github.mbackschat.a12.dm.interpreter.conformance.Op

/**
 * The JVM conformance **replay runner** (CONFORMANCE-CORPUS-SPEC §12) — replays a committed [ConformanceCase]
 * through all three JVM engines ([CorpusEngines]) and returns one [Verdict] per engine:
 *
 * - **PASS** — the engine's rendered outcome multiset equals the applicable expectation.
 * - **FAIL** — any difference (the report names the actual-only / expected-only signatures).
 * - **SKIP** — only the interpreter, and only when it declaredly cannot evaluate a construct (reports it as
 *   `unsupported`); never silent, never a pass.
 *
 * The Groovy-dynamic and java-static legs compare against the anchor `expected`/`computeExpected`, java-static
 * consulting a `divergences` override when present. The groovy leg re-runs the real kernel, so it is also the
 * **freshness guard**: a kernel change that alters a committed expectation fails here (the KERNEL-UPGRADE
 * guardian's re-capture signal).
 */
object CorpusReplay {

    enum class Verdict { PASS, FAIL, SKIP }

    data class Result(val verdict: Verdict, val detail: String = "")

    /** Replay [case] three ways; keys are [CorpusEngines.INTERPRETER] / [CorpusEngines.GROOVY_DYNAMIC] / [CorpusEngines.JAVA_STATIC]. */
    fun replay(case: ConformanceCase): Map<String, Result> {
        val anchor = applicableExpected(case)
            ?: throw IllegalArgumentException("case '${case.meta.id}' has neither expected nor computeExpected")

        val interp = CorpusEngines.interpretDetailed(case.model, case.placements, case.op, case.clock)
        val interpResult = if (interp.unsupported.isNotEmpty()) Result(Verdict.SKIP, interp.unsupported.joinToString("; "))
        else compare(interp.signatures, anchor)

        val groovy = compare(CorpusEngines.groovyDynamic(case.model, case.placements, case.op, case.clock), anchor)

        val javaExpected = overrideFor(case, CorpusEngines.JAVA_STATIC) ?: anchor
        val java = compare(CorpusEngines.javaStatic(case.model, case.placements, case.op, case.clock), javaExpected)

        return mapOf(
            CorpusEngines.INTERPRETER to interpResult,
            CorpusEngines.GROOVY_DYNAMIC to groovy,
            CorpusEngines.JAVA_STATIC to java,
        )
    }

    private fun compare(actual: List<String>, expected: List<String>): Result =
        if (actual == expected) Result(Verdict.PASS)
        else Result(Verdict.FAIL, "actual-only=${actual - expected.toSet()} expected-only=${expected - actual.toSet()}")

    /** The expectation the op's outcome is compared to — computeExpected for a compute op, else expected. */
    private fun applicableExpected(case: ConformanceCase): List<String>? =
        if (case.op is Op.Compute) case.computeExpected else case.expected

    /** The per-strategy divergence override for [strategy], in the op's key, or null when the anchor applies. */
    private fun overrideFor(case: ConformanceCase, strategy: String): List<String>? =
        case.divergences.firstOrNull { it.strategy == strategy }
            ?.let { if (case.op is Op.Compute) it.computeExpected else it.expected }
}
