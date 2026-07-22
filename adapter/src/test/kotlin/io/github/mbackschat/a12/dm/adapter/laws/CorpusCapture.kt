package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseMeta
import io.github.mbackschat.a12.dm.interpreter.conformance.ConformanceCase
import io.github.mbackschat.a12.dm.interpreter.conformance.Divergence
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader

/**
 * **Conformance corpus capture** (CONFORMANCE-CORPUS-SPEC §13) — turns a scenario `(model, placements, op,
 * clock?)` into a committed [ConformanceCase], **tri-verified** against all three JVM engines before it is
 * allowed to exist:
 *
 * - `expected`/`computeExpected` records the **Groovy-dynamic anchor** (S3).
 * - a **java-static** split from the anchor becomes a `divergences` entry (a kernel characterization, never the
 *   anchor).
 * - an **interpreter** split **blocks** the case: [capture] throws [InterpreterDivergence] with the minimized
 *   diff, which the caller records as an INTERPRETER-GAPS row and hands to a fresh BEST_MODEL session (WI-T, §5)
 *   — a case the interpreter fails is never committed.
 *
 * The pinned kernel version is stamped from [CorpusEngines.KERNEL_VERSION], so `meta.kernelVersion` cannot drift
 * from what actually produced the expectations.
 */
object CorpusCapture {

    /** A captured case's interpreter leg disagreed with the Groovy-dynamic anchor — the WI-T handoff (§5). */
    class InterpreterDivergence(val caseId: String, val anchor: List<String>, val interp: List<String>) :
        RuntimeException(buildString {
            append("interpreter diverges from the groovy-dynamic anchor on case '$caseId' — case BLOCKED (WI-T).\n")
            append("  anchor-only  = ${anchor - interp.toSet()}\n")
            append("  interp-only  = ${interp - anchor.toSet()}")
        })

    /**
     * Tri-verify [scenario] and build its [ConformanceCase], referencing the standalone model file
     * `models/[modelName].json` via [ConformanceCase.modelRef] (the model text is written separately by the
     * generator). Throws [InterpreterDivergence] if the interpreter disagrees with the anchor (the case is not
     * committable). [meta]'s `kernelVersion` is overwritten with the pinned version.
     */
    fun capture(meta: CaseMeta, modelName: String, modelJson: String, placements: List<Placement>, op: Op, clock: String? = null): ConformanceCase {
        val anchor = CorpusEngines.groovyDynamic(modelJson, placements, op, clock)
        // The interpreter reports EVERY computed instance; the kernel corpus anchor captures only its
        // three-channel change projection (the full result has five channels — KERNEL-FINDINGS §11) — so
        // project the interpreter's compute outcome to that granularity before comparing (SPEC §7).
        val interpAll = CorpusEngines.interpret(modelJson, placements, op, clock)
        val interp =
            if (op is Op.Compute) kernelComputeGranularity(interpAll, placements, ModelLoader.load(modelJson))
            else interpAll
        if (interp != anchor) throw InterpreterDivergence(meta.id, anchor, interp)

        val java = CorpusEngines.javaStatic(modelJson, placements, op, clock)
        val isCompute = op is Op.Compute
        val divergences = if (java == anchor) emptyList() else listOf(
            if (isCompute) Divergence(CorpusEngines.JAVA_STATIC, computeExpected = java)
            else Divergence(CorpusEngines.JAVA_STATIC, expected = java),
        )
        return ConformanceCase(
            meta = meta.copy(kernelVersion = CorpusEngines.KERNEL_VERSION),
            modelRef = modelRefOf(modelName),
            placements = placements,
            op = op,
            clock = clock,
            expected = if (isCompute) null else anchor,
            computeExpected = if (isCompute) anchor else null,
            divergences = divergences,
        )
    }

    /** The corpus-root-relative model path a case references for the domain model [modelName]. */
    fun modelRefOf(modelName: String): String = "models/$modelName.json"
}
