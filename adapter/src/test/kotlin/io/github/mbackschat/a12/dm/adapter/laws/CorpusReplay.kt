package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.Op

/**
 * The JVM conformance **replay runner** (CONFORMANCE-CORPUS-SPEC §12) — replays a committed [ConformanceCase]
 * through all three JVM engines and returns one [InterpreterReplay.Result] per engine:
 *
 * - **PASS** — the engine's rendered outcome multiset equals the applicable expectation.
 * - **FAIL** — any difference (the report names the actual-only / expected-only signatures).
 * - **SKIP** — only the interpreter, and only when it declaredly cannot evaluate a construct; never a pass.
 *
 * The interpreter leg is the multiplatform [InterpreterReplay] (the exact engine the JS replay runs). The
 * Groovy-dynamic and java-static legs compare against the anchor `expected`/`computeExpected`, java-static
 * consulting a `divergences` override when present. The groovy leg re-runs the real kernel, so it is also the
 * **freshness guard**: a kernel change that alters a committed expectation fails here (the KERNEL-UPGRADE
 * guardian's re-capture signal).
 */
object CorpusReplay {

    /** Replay [case] (its model resolved to [modelJson]) three ways; keys are [CorpusEngines.INTERPRETER] / [CorpusEngines.GROOVY_DYNAMIC] / [CorpusEngines.JAVA_STATIC]. */
    fun replay(case: ConformanceCase, modelJson: String): Map<String, InterpreterReplay.Result> {
        val anchor = InterpreterReplay.anchorOf(case)

        val interp = InterpreterReplay.replay(case, modelJson)

        val groovy = InterpreterReplay.compare(
            CorpusEngines.groovyDynamic(modelJson, case.placements, case.op, case.clock), anchor)

        val javaExpected = overrideFor(case, CorpusEngines.JAVA_STATIC) ?: anchor
        val java = InterpreterReplay.compare(
            CorpusEngines.javaStatic(modelJson, case.placements, case.op, case.clock), javaExpected)

        return mapOf(
            CorpusEngines.INTERPRETER to interp,
            CorpusEngines.GROOVY_DYNAMIC to groovy,
            CorpusEngines.JAVA_STATIC to java,
        )
    }

    /** The per-strategy divergence override for [strategy], in the op's key, or null when the anchor applies. */
    private fun overrideFor(case: ConformanceCase, strategy: String): List<String>? =
        case.divergences.firstOrNull { it.strategy == strategy }
            ?.let { if (case.op is Op.Compute) it.computeExpected else it.expected }
}
