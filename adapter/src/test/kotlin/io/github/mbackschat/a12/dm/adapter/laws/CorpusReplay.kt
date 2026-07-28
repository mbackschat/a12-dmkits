package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.overrideFor
import io.github.mbackschat.a12.dm.interpreter.conformance.placementsForKernelLeg
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader

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
        // The kernel legs build their own document from placement records; the INTERPRETER leg reads the case's
        // canonical Document JSON through the public reader (IF230). Step 5 moves the kernel legs onto A12's own
        // deserializeV2 so both sides share one ingress regime.
        val kernelPlacements = case.placementsForKernelLeg(ModelLoader.load(modelJson))

        val interp = InterpreterReplay.replay(case, modelJson)

        val groovy = InterpreterReplay.compare(
            CorpusEngines.groovyDynamic(modelJson, kernelPlacements, case.op, case.clock), anchor)

        val javaExpected = case.overrideFor(CorpusEngines.JAVA_STATIC) ?: anchor
        val java = InterpreterReplay.compare(
            CorpusEngines.javaStatic(modelJson, kernelPlacements, case.op, case.clock), javaExpected)

        return mapOf(
            CorpusEngines.INTERPRETER to interp,
            CorpusEngines.GROOVY_DYNAMIC to groovy,
            CorpusEngines.JAVA_STATIC to java,
        )
    }
}
